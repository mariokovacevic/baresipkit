package io.github.mariokovacevic.baresipkit

import io.github.mariokovacevic.baresipkit.internal.EventDecoder
import io.github.mariokovacevic.baresipkit.internal.Native
import io.github.mariokovacevic.baresipkit.internal.NativeAccountConfig
import io.github.mariokovacevic.baresipkit.internal.NativeEngineConfig
import io.github.mariokovacevic.baresipkit.internal.cString
import io.github.mariokovacevic.baresipkit.internal.cStringOrNull
import io.github.mariokovacevic.baresipkit.internal.names
import io.github.mariokovacevic.baresipkit.internal.values
import io.github.mariokovacevic.baresipkit.internal.wholeMilliseconds
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A SIP user agent backed by baresip.
 *
 * Create one engine per app, start it, add an account and place or answer calls. All operations
 * are `suspend` functions: they run on the engine's own thread and return when the core has
 * accepted them. What happens afterwards arrives through [events].
 *
 * ```kotlin
 * val engine = BaresipEngine(BaresipConfiguration(instanceUuid = storedInstanceUuid))
 * engine.start()
 * val account = engine.addAccount(BaresipAccountConfiguration(aor = "sip:alice@example.com") { authPassword = password })
 * engine.register(account)
 * val call = engine.makeCall(destination = "sip:bob@example.com")
 * ```
 *
 * Close the engine with [close] when it is no longer needed; it stops and frees the core.
 *
 * @param configuration Engine-wide settings.
 * @throws BaresipError when the core rejects the configuration.
 */
public class BaresipEngine(
    /** The configuration the engine was created with. */
    public val configuration: BaresipConfiguration,
) : AutoCloseable {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "baresipkit.engine").apply { isDaemon = true }
        }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)
    private val handleLock = ReentrantReadWriteLock()
    private val eventFlow = MutableSharedFlow<Any>(extraBufferCapacity = Int.MAX_VALUE)

    @Volatile private var eventsEnded = false

    @Volatile private var logHandler: BaresipLogHandler? = null
    private val videoSinks = HashMap<VideoSinkKey, VideoSinkBox>()
    private val handle: Long

    init {
        handle =
            try {
                Native.engineCreate(this, NativeEngineConfig(configuration))
            } catch (error: Throwable) {
                executor.shutdown()
                throw error
            }
    }

    /**
     * Engine events.
     *
     * Every collector receives every event that happens after its collection started. A collection
     * launched with `CoroutineStart.UNDISPATCHED` has started when `launch` returns, so an operation
     * made after it cannot be missed:
     *
     * ```kotlin
     * scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { handle(it) } }
     * engine.start()
     * ```
     *
     * The flow completes when the engine is closed with [close], after the last event of the engine;
     * collecting the events of a closed engine completes at once. Collectors such as a `collect` loop
     * therefore end with the engine instead of waiting forever.
     *
     * The events are buffered without limit, so the core never waits for a slow collector and no
     * event is dropped, periodic [BaresipEvent.Statistics] included. A collector that stops reading
     * without ending its collection keeps every later event in memory; cancel a collection that is no
     * longer needed.
     */
    public val events: Flow<BaresipEvent> =
        eventFlow
            .onSubscription { if (eventsEnded) emit(EndOfEvents) }
            .transformWhile { item ->
                if (item is BaresipEvent) emit(item)
                item is BaresipEvent
            }

    /** Sets the handler that receives engine log output, or removes it with `null`. */
    public fun setLogHandler(handler: BaresipLogHandler?) {
        logHandler = handler
    }

    /** The current lifecycle state. */
    public val state: BaresipEngineState
        get() = withHandle(BaresipEngineState.STOPPED) { BaresipEngineState.of(Native.engineState(it)) }

    internal val isEngineThread: Boolean
        get() = withHandle(false) { Native.engineIsEngineThread(it) }

    /**
     * Starts the engine: loads modules, opens transports and starts the engine thread. Only one
     * engine may run per process.
     *
     * @throws BaresipError when the engine is not idle, another engine is running
     *   ([BaresipError.Code.BUSY]) or the core cannot start.
     */
    public suspend fun start(): Unit = onEngine { Native.engineStart(handle) }

    /**
     * Stops the engine. Calls are hung up and accounts unregister, within [timeout]. The engine's
     * accounts are removed; add them again after the next [start].
     */
    public suspend fun stop(timeout: Duration = 3.seconds) {
        ignoringErrors { onEngine { Native.engineStop(handle, timeout.wholeMilliseconds()) } }
    }

    /**
     * Prepares the engine for an incoming call announced by a push message.
     *
     * A running engine sends a REGISTER for every account that is registered or registering, so
     * the INVITE can arrive on a fresh connection. A stopped engine is started, without
     * accounts: add and register them afterwards.
     */
    public suspend fun prepareForPushWakeUp(): Unit = onEngine { Native.enginePrepareForPushWakeup(handle) }

    /** Suspends the engine when the app moves to the background without an active call. */
    public suspend fun suspend(): Unit = onEngine { Native.engineSuspend(handle) }

    /** Resumes an engine suspended with [suspend]. */
    public suspend fun resumeFromBackground(): Unit = onEngine { Native.engineResume(handle) }

    /**
     * Tells the engine that the network changed, for example from a `ConnectivityManager`
     * callback. The engine reads the local addresses again and opens its transports on them.
     *
     * @param reregister Whether accounts register again on the new address.
     * @param updateCalls Whether active calls move to the new address, restarting ICE when used.
     */
    public suspend fun networkDidChange(
        reregister: Boolean = true,
        updateCalls: Boolean = true,
    ) {
        ignoringErrors { onEngine { Native.engineNetworkChanged(handle, reregister, updateCalls) } }
    }

    /** Tells the engine that audio focus was gained and the audio device may start. */
    public suspend fun audioSessionDidActivate() {
        ignoringErrors { onEngine { Native.engineAudioSessionActivated(handle) } }
    }

    /** Tells the engine that audio focus was lost for good. */
    public suspend fun audioSessionDidDeactivate() {
        ignoringErrors { onEngine { Native.engineAudioSessionDeactivated(handle) } }
    }

    /** Tells the engine that the audio route changed, so audio streams are restarted. */
    public suspend fun audioRouteDidChange() {
        ignoringErrors { onEngine { Native.engineAudioRouteChanged(handle) } }
    }

    /** Tells the engine that an audio interruption, such as a cellular call, began or ended. */
    public suspend fun audioInterruption(began: Boolean) {
        ignoringErrors { onEngine { Native.engineAudioInterruption(handle, began) } }
    }

    /**
     * Adds an account. The first account added becomes the default for calls and messages.
     * Adding does not register; call [register].
     *
     * When the calling coroutine is cancelled after the core added the account, the account is
     * removed again before the cancellation is thrown.
     *
     * @throws BaresipError when the settings are invalid or the engine is not running.
     */
    public suspend fun addAccount(account: BaresipAccountConfiguration): BaresipAccountID {
        account.validate()
        val native = NativeAccountConfig(account)
        return BaresipAccountID(
            onEngineCreating(create = { Native.accountAdd(handle, native) }, discard = { Native.accountRemove(handle, it) }),
        )
    }

    /** Unregisters and removes an account. Its calls are hung up. */
    public suspend fun removeAccount(id: BaresipAccountID): Unit = onEngine { Native.accountRemove(handle, id.rawValue) }

    /** Makes an account the default for calls and messages without an explicit account. */
    public suspend fun setDefaultAccount(id: BaresipAccountID): Unit = onEngine { Native.accountSetDefault(handle, id.rawValue) }

    /** Starts registration. Progress arrives as [BaresipEvent.Registration]. */
    public suspend fun register(account: BaresipAccountID): Unit = onEngine { Native.accountRegister(handle, account.rawValue) }

    /** Removes the registration. */
    public suspend fun unregister(account: BaresipAccountID): Unit = onEngine { Native.accountUnregister(handle, account.rawValue) }

    /**
     * Replaces an account's credentials. The next REGISTER uses them.
     *
     * @param authUser The authentication user name, or `null` for the user part of the address.
     */
    public suspend fun updateCredentials(
        account: BaresipAccountID,
        authUser: String?,
        password: String,
    ): Unit = onEngine { Native.accountSetCredentials(handle, account.rawValue, authUser.cStringOrNull(), password.cString()) }

    /**
     * Replaces an account's `Contact` parameters, for example when the push token changes.
     *
     * @param reregisterNow Whether to send a REGISTER with the new parameters right away.
     */
    public suspend fun updateContactParameters(
        account: BaresipAccountID,
        parameters: List<BaresipContactParameter>,
        reregisterNow: Boolean = true,
    ) {
        encodeContactParameters(parameters)
        onEngine {
            Native.accountSetContactParams(handle, account.rawValue, parameters.names(), parameters.values(), reregisterNow)
        }
    }

    /**
     * The accounts the engine has, each with its current registration state. The result is empty
     * while the engine is not running.
     */
    public suspend fun accounts(): Map<BaresipAccountID, BaresipRegistrationState> =
        ignoringErrors(emptyMap()) {
            onEngine {
                val pairs = Native.accountList(handle)
                (pairs.indices step 2).associate {
                    BaresipAccountID(pairs[it]) to BaresipRegistrationState.of(pairs[it + 1].toInt())
                }
            }
        }

    /** The current registration state of an account, or [BaresipRegistrationState.NONE] for an unknown account. */
    public fun registrationState(account: BaresipAccountID): BaresipRegistrationState =
        withHandle(BaresipRegistrationState.NONE) { BaresipRegistrationState.of(Native.accountRegistrationState(it, account.rawValue)) }

    /**
     * Places a call.
     *
     * A destination without a scheme and domain, such as `1002`, is completed with the account's
     * domain. Video is always offered, so the callee's early media and video are shown even in an
     * audio call; [video] only decides whether your camera is sent.
     *
     * When the calling coroutine is cancelled after the core created the call, the call is hung
     * up before the cancellation is thrown.
     *
     * @param account The account to call from, or `null` for the default account.
     * @param destination A SIP URI, user name or number.
     * @param video Whether to send your camera.
     * @param audio Whether audio flows. With `false` the audio stream is inactive in both
     *   directions, for example to watch a door station's camera.
     * @return The new call's identifier. State changes arrive as [BaresipEvent.Call].
     */
    public suspend fun makeCall(
        account: BaresipAccountID? = null,
        destination: String,
        video: Boolean = false,
        audio: Boolean = true,
    ): BaresipCallID =
        BaresipCallID(
            onEngineCreating(
                create = { Native.callMake(handle, account?.rawValue ?: 0, destination.cString(), audio, video) },
                discard = { Native.callHangup(handle, it) },
            ),
        )

    /**
     * Answers an incoming call. The caller's video, if offered, is always received; [video] only
     * decides whether your camera is sent.
     *
     * @param audio Whether audio flows. With `false` the call is answered silently.
     */
    public suspend fun answer(
        call: BaresipCallID,
        video: Boolean = false,
        audio: Boolean = true,
    ): Unit = onEngine { Native.callAnswer(handle, call.rawValue, audio, video) }

    /**
     * Starts a receive-only early media preview of an incoming call with 183 Session Progress.
     * You hear the caller, and with [earlyVideo] see them; the caller neither hears nor sees you
     * until [answer].
     */
    public suspend fun sendProgress(
        call: BaresipCallID,
        earlyVideo: Boolean = false,
        earlyAudio: Boolean = true,
    ): Unit = onEngine { Native.callProgress(handle, call.rawValue, earlyAudio, earlyVideo) }

    /**
     * Rejects an incoming call that is still ringing. End an answered call with [hangUp].
     *
     * @param statusCode The SIP response code, 486 Busy Here by default.
     * @param reason The reason phrase, or `null` for the standard phrase.
     */
    public suspend fun reject(
        call: BaresipCallID,
        statusCode: Int = 486,
        reason: String? = null,
    ): Unit = onEngine { Native.callReject(handle, call.rawValue, statusCode.coerceIn(0, MAXIMUM_SIP_STATUS), reason.cStringOrNull()) }

    /** Hangs up a call in any state. */
    public suspend fun hangUp(call: BaresipCallID): Unit = onEngine { Native.callHangup(handle, call.rawValue) }

    /**
     * Puts a call on hold. When the other side rejects the hold, the call returns to its previous
     * state with an error in the event.
     */
    public suspend fun hold(call: BaresipCallID): Unit = onEngine { Native.callHold(handle, call.rawValue, true) }

    /** Resumes a call from hold. */
    public suspend fun resume(call: BaresipCallID): Unit = onEngine { Native.callHold(handle, call.rawValue, false) }

    /** Makes a call the active one: other established calls are put on hold, and this call is resumed. */
    public suspend fun setActiveCall(call: BaresipCallID): Unit = onEngine { Native.callSetActive(handle, call.rawValue) }

    /**
     * Sends DTMF digits from `0`–`9`, `*`, `#` and `A`–`D`.
     *
     * @param duration How long each digit is sent.
     * @param gap The pause between digits.
     */
    public suspend fun sendDtmf(
        call: BaresipCallID,
        digits: String,
        duration: Duration = 160.milliseconds,
        gap: Duration = 80.milliseconds,
    ): Unit =
        onEngine {
            Native.callSendDtmf(handle, call.rawValue, digits.cString(), duration.wholeMilliseconds(), gap.wholeMilliseconds())
        }

    /** Transfers a call to another party without consulting them first (blind transfer). */
    public suspend fun blindTransfer(
        call: BaresipCallID,
        targetUri: String,
    ): Unit = onEngine { Native.callTransfer(handle, call.rawValue, targetUri.cString()) }

    /**
     * Connects the remote party of [call] to the party of [consultationCall] and leaves both calls
     * (attended transfer, REFER with Replaces).
     */
    public suspend fun attendedTransfer(
        call: BaresipCallID,
        consultationCall: BaresipCallID,
    ): Unit = onEngine { Native.callTransferAttended(handle, call.rawValue, consultationCall.rawValue) }

    /** A snapshot of a call. */
    public suspend fun callInfo(call: BaresipCallID): BaresipCallInfo =
        onEngine {
            val numbers = LongArray(EventDecoder.CALL_INFO_LONGS)
            val strings = Native.callInfo(handle, call.rawValue, numbers)
            EventDecoder.callInfo(numbers, strings)
        }

    /**
     * A header of the INVITE that started an incoming call, such as `X-Device-ID`. Only headers
     * named in [BaresipAccountConfiguration.incomingHeaders] are kept.
     *
     * @return The header value, or `null` when the INVITE had no such header.
     */
    public suspend fun incomingHeader(
        call: BaresipCallID,
        name: String,
    ): String? = onEngine { Native.callHeader(handle, call.rawValue, name.cString()) }

    /** The identifiers of all calls that are not released, oldest first. */
    public suspend fun activeCalls(): List<BaresipCallID> = ignoringErrors(emptyList()) { onEngine { Native.callList(handle).map(::BaresipCallID) } }

    /** Current media statistics for a call. */
    public suspend fun callStatistics(call: BaresipCallID): BaresipCallStatistics =
        onEngine {
            val numbers = LongArray(EventDecoder.STATISTICS_LONGS)
            val strings = Native.callStatistics(handle, call.rawValue, numbers)
            EventDecoder.statistics(numbers, strings)
        }

    /** Mutes or unmutes the microphone for a call. */
    public suspend fun setMicrophoneMuted(
        call: BaresipCallID,
        muted: Boolean,
    ): Unit = onEngine { Native.callSetMicrophoneMuted(handle, call.rawValue, muted) }

    /** Changes the audio direction of a call with a re-INVITE. */
    public suspend fun setAudioDirection(
        call: BaresipCallID,
        direction: BaresipMediaDirection,
    ): Unit = onEngine { Native.callSetAudioDirection(handle, call.rawValue, direction.ordinal) }

    /** Changes the video direction of a call with a re-INVITE. */
    public suspend fun setVideoDirection(
        call: BaresipCallID,
        direction: BaresipMediaDirection,
    ): Unit = onEngine { Native.callSetVideoDirection(handle, call.rawValue, direction.ordinal) }

    /**
     * Turns sending your camera on or off for a call. Only sending changes: with `false` the
     * direction becomes receive-only, so you keep seeing the other side.
     */
    public suspend fun setVideoEnabled(
        call: BaresipCallID,
        enabled: Boolean,
    ): Unit = setVideoDirection(call, if (enabled) BaresipMediaDirection.SEND_RECEIVE else BaresipMediaDirection.RECEIVE_ONLY)

    /** Switches the camera used for a call to a device from [videoDevices]. */
    public suspend fun setCamera(
        call: BaresipCallID,
        device: String,
    ): Unit = onEngine { Native.callSetCamera(handle, call.rawValue, device.cString()) }

    /** Asks the encoder to send a keyframe. */
    public suspend fun requestKeyframe(call: BaresipCallID): Unit = onEngine { Native.callRequestKeyframe(handle, call.rawValue) }

    /** Caps the video bitrate of a call below the engine's configured bitrate, or removes the cap with `null`. */
    public suspend fun setVideoMaxBitrate(
        call: BaresipCallID,
        bitsPerSecond: Int?,
    ): Unit = onEngine { Native.callSetVideoMaxBitrate(handle, call.rawValue, (bitsPerSecond ?: 0).coerceAtLeast(0)) }

    /** Restarts ICE for a call, for example after the network changed. */
    public suspend fun restartIce(call: BaresipCallID): Unit = onEngine { Native.callRestartIce(handle, call.rawValue) }

    /**
     * Starts recording a call to a 16-bit stereo WAV file: the left channel is the microphone and
     * the right channel the remote party. An existing file is replaced. Check local law before
     * recording calls.
     */
    public suspend fun startRecording(
        call: BaresipCallID,
        file: File,
    ): Unit = onEngine { Native.callRecordStart(handle, call.rawValue, file.path.cString()) }

    /** Stops recording and completes the file. */
    public suspend fun stopRecording(call: BaresipCallID): Unit = onEngine { Native.callRecordStop(handle, call.rawValue) }

    /**
     * Sends a text message as a SIP MESSAGE (RFC 3428). The result arrives later as
     * [BaresipEvent.MessageStatus] with the returned identifier.
     *
     * A message the core has accepted cannot be withdrawn: when the calling coroutine is cancelled
     * after that, the message is still sent and its [BaresipEvent.MessageStatus] still arrives.
     *
     * @param account The account to send from, or `null` for the default account.
     */
    public suspend fun sendMessage(
        peerUri: String,
        text: String,
        account: BaresipAccountID? = null,
    ): BaresipMessageID = BaresipMessageID(onEngine { Native.messageSend(handle, account?.rawValue ?: 0, peerUri.cString(), text.cString()) })

    /** The names of the cameras the video source offers. */
    public suspend fun videoDevices(): List<String> = ignoringErrors(emptyList()) { onEngine { Native.videoDevices(handle).split('\n').filter { it.isNotEmpty() } } }

    /**
     * Attaches a renderer to a call's remote or local video, or detaches it with `null`. The
     * engine keeps the renderer until it is replaced, detached or the call is released.
     *
     * @param local `true` for the camera image sent to the peer, `false` for the remote video.
     */
    public suspend fun setVideoRenderer(
        call: BaresipCallID,
        renderer: BaresipVideoRenderer?,
        local: Boolean = false,
    ): Unit =
        replaceSink(VideoSinkKey(call, local), renderer?.let(::VideoSinkBox)) { box ->
            Native.callSetVideoSink(handle, call.rawValue, local, box?.pointer ?: 0)
        }

    /**
     * Starts the camera outside a call and sends its frames to a renderer.
     *
     * @param device A device name from [videoDevices], or `null` for the default camera.
     */
    public suspend fun startVideoPreview(
        device: String? = null,
        renderer: BaresipVideoRenderer,
    ): Unit =
        replaceSink(VideoSinkKey(null, true), VideoSinkBox(renderer)) { box ->
            Native.videoPreviewStart(handle, device.cStringOrNull(), box?.pointer ?: 0)
        }

    /** Stops the camera preview. */
    public suspend fun stopVideoPreview() {
        onEngine { Native.videoPreviewStop(handle) }
        synchronized(videoSinks) { videoSinks.remove(VideoSinkKey(null, true)) }?.release()
    }

    /** Switches the camera used for a call to the front or back camera. */
    public suspend fun setCamera(
        call: BaresipCallID,
        position: BaresipCameraPosition,
    ): Unit = setCamera(call, position.deviceName)

    private suspend fun replaceSink(
        key: VideoSinkKey,
        box: VideoSinkBox?,
        install: (VideoSinkBox?) -> Unit,
    ) {
        try {
            onEngine { install(box) }
        } catch (error: Throwable) {
            box?.release()
            throw error
        }
        val previous = synchronized(videoSinks) { if (box == null) videoSinks.remove(key) else videoSinks.put(key, box) }
        previous?.release()
    }

    private fun clearVideoSinks(call: BaresipCallID) {
        val removed =
            synchronized(videoSinks) {
                listOfNotNull(videoSinks.remove(VideoSinkKey(call, true)), videoSinks.remove(VideoSinkKey(call, false)))
            }
        removed.forEach { it.release() }
    }

    /**
     * Stops and frees the engine. Pending operations fail with [BaresipError.Code.INVALID_STATE];
     * [events] delivers the engine's last events and then completes.
     *
     * `close` blocks the calling thread until the core has stopped: the operations already queued
     * on the engine finish first, then calls are hung up and accounts unregister, which takes up to
     * 3 seconds. The wait is bounded by 10 seconds; after that `close` returns and the core is freed
     * in the background, and starting another engine before then fails with
     * [BaresipError.Code.BUSY]. Call it from a background thread, for example with
     * `withContext(Dispatchers.IO) { engine.close() }`, not from the main thread. Called on the
     * engine's own thread, it returns at once.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            handleLock.write { Native.engineDestroy(handle) }
            synchronized(videoSinks) { videoSinks.values.toList().also { videoSinks.clear() } }.forEach { it.release() }
            eventsEnded = true
            eventFlow.tryEmit(EndOfEvents)
        }
        executor.shutdown()
        if (Thread.currentThread().name != THREAD_NAME) {
            executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    internal fun deliver(event: BaresipEvent) {
        if (event is BaresipEvent.Call && event.state == BaresipCallState.RELEASED) clearVideoSinks(event.call)
        eventFlow.tryEmit(event)
    }

    internal fun deliverLog(entry: BaresipLogEntry) {
        logHandler?.log(entry)
    }

    private inline fun <T> withHandle(
        ifClosed: T,
        body: (Long) -> T,
    ): T {
        val lock = handleLock.readLock()
        if (!lock.tryLock()) return ifClosed
        try {
            return if (closed.get()) ifClosed else body(handle)
        } finally {
            lock.unlock()
        }
    }

    private inline fun ignoringErrors(body: () -> Unit) {
        ignoringErrors(Unit, body)
    }

    private inline fun <T> ignoringErrors(
        fallback: T,
        body: () -> T,
    ): T =
        try {
            body()
        } catch (ignored: BaresipError) {
            fallback
        }

    private suspend fun onEngineCreating(
        create: () -> Long,
        discard: (Long) -> Unit,
    ): Long {
        var created: Long? = null
        try {
            return onEngine { create().also { created = it } }
        } catch (cancelled: CancellationException) {
            created?.let { id -> withContext(NonCancellable) { ignoringErrors { onEngine { discard(id) } } } }
            throw cancelled
        }
    }

    private suspend fun <T> onEngine(body: () -> T): T {
        if (closed.get()) throw BaresipError(BaresipError.Code.INVALID_STATE, detail = "engine is closed")
        return try {
            withContext(dispatcher) {
                if (closed.get()) throw BaresipError(BaresipError.Code.INVALID_STATE, detail = "engine is closed")
                body()
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw BaresipError(BaresipError.Code.INVALID_STATE, detail = "engine is closed")
        }
    }

    private object EndOfEvents

    /** What the linked core offers without an engine: its version and the `Contact` parameter encoding. */
    public companion object {
        private const val THREAD_NAME = "baresipkit.engine"
        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private const val MAXIMUM_SIP_STATUS = 699

        internal val abiVersion: AbiVersion
            get() = Native.abiVersion().let { AbiVersion(it ushr 16, it and 0xffff) }

        internal val capabilities: List<String>
            get() = Native.capabilities().split(' ').filter { it.isNotEmpty() }

        /** The version string of the linked core. */
        public val coreVersion: String
            get() = Native.version()

        /**
         * Encodes contact parameters exactly as they will appear in the `Contact` header, for
         * example `;pn-provider=fcm;pn-prid=…`.
         *
         * @throws BaresipError with [BaresipError.Code.INVALID_ARGUMENT] when a name or value is
         *   not a valid SIP token.
         */
        public fun encodeContactParameters(parameters: List<BaresipContactParameter>): String = Native.encodeContactParameters(parameters.names(), parameters.values())
    }

    internal data class AbiVersion(
        val major: Int,
        val minor: Int,
    )
}
