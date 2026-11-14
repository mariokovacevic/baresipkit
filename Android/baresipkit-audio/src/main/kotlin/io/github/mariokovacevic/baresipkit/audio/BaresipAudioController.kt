package io.github.mariokovacevic.baresipkit.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.mariokovacevic.baresipkit.BaresipEngine
import io.github.mariokovacevic.baresipkit.BaresipError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Puts the device's audio in communication mode for calls and keeps the engine informed about
 * it, the counterpart of the iOS audio session controller.
 *
 * With a self-managed `ConnectionService`, Telecom owns audio focus; forward its callbacks with
 * [didActivateFromTelecom] and [didDeactivateFromTelecom]. Without Telecom, or to hear an early
 * media preview before answering, call [activate] yourself: it requests audio focus for voice
 * communication and sets `MODE_IN_COMMUNICATION`.
 *
 * Losing audio focus, for example to a cellular call, pauses the engine's audio. When focus comes
 * back the engine resumes if [activate] had activated it; with Telecom the audio resumes with
 * the next [didActivateFromTelecom]. A headset or Bluetooth device that connects or disconnects
 * restarts the engine's audio streams. The engine is told about these changes, and about
 * [activate], [deactivate] and the Telecom calls, in the order they happen.
 *
 * ```kotlin
 * val audio = BaresipAudioController(context, engine)
 * audio.activate()
 * audio.selectOutput(BaresipAudioOutput.SPEAKER)
 * ```
 *
 * @param onRouteChange Called on the main thread with the new route after each change.
 */
public class BaresipAudioController(
    context: Context,
    private val engine: BaresipEngine,
    private val onRouteChange: ((BaresipAudioRoute) -> Unit)? = null,
) {
    internal enum class Activation { INACTIVE, CONTROLLER, TELECOM }

    internal enum class FocusResponse { PAUSE_ENGINE, RESUME_ENGINE, STAY }

    internal enum class ScoLink { UP, DOWN, PENDING }

    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class EngineUpdate(
        val apply: suspend BaresipEngine.() -> Unit,
        val done: CompletableDeferred<Unit>? = null,
    )

    private val engineUpdates =
        Channel<EngineUpdate>(Channel.UNLIMITED, onUndeliveredElement = { it.done?.complete(Unit) })

    @Volatile private var activation = Activation.INACTIVE
    private var focusRequest: AudioFocusRequest? = null
    private var defaultToSpeaker = false

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (focusResponse(change, activation)) {
                FocusResponse.PAUSE_ENGINE -> tellEngine { audioInterruption(began = true) }
                FocusResponse.RESUME_ENGINE -> tellEngine { audioInterruption(began = false) }
                FocusResponse.STAY -> Unit
            }
        }

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged(addedDevices)

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged(removedDevices)
        }

    private val communicationDeviceListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                onRouteChange?.invoke(device?.let { audioRouteOf(it.type) } ?: BaresipAudioRoute.Other(0))
            }
        } else {
            null
        }

    @Volatile private var scoReceiver: BroadcastReceiver? = null

    init {
        scope.launch {
            for (update in engineUpdates) {
                try {
                    engine.(update.apply)()
                    update.done?.complete(Unit)
                } catch (error: BaresipError) {
                    update.done?.completeExceptionally(error)
                } finally {
                    update.done?.complete(Unit)
                }
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnCommunicationDeviceChangedListener(
                context.mainExecutor,
                communicationDeviceListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
        }
    }

    /**
     * Sets whether calls start on the loudspeaker, for example for a door intercom. The setting
     * applies with the next [activate].
     */
    public fun configureForVoip(defaultToSpeaker: Boolean = false) {
        this.defaultToSpeaker = defaultToSpeaker
    }

    /**
     * Requests audio focus for voice communication, sets `MODE_IN_COMMUNICATION` and tells the
     * engine. Use this without Telecom.
     *
     * @return `false` when the system refused audio focus, for example during a cellular call.
     */
    public suspend fun activate(): Boolean {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (defaultToSpeaker) selectOutput(BaresipAudioOutput.SPEAKER)
        activation = Activation.CONTROLLER
        tellEngineAndWait { audioSessionDidActivate() }
        return true
    }

    /**
     * Tells the engine, leaves communication mode and gives up audio focus. Before Android 12 it
     * also stops the Bluetooth SCO link [selectOutput] started.
     */
    public suspend fun deactivate() {
        activation = Activation.INACTIVE
        tellEngineAndWait { audioSessionDidDeactivate() }
        clearCommunicationDevice()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) stopSco()
        audioManager.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Forwards the activation of a Telecom connection's audio, for example `onCallAudioStateChanged`, to the engine. */
    public suspend fun didActivateFromTelecom() {
        activation = Activation.TELECOM
        tellEngineAndWait { audioSessionDidActivate() }
    }

    /** Forwards the end of a Telecom connection's audio to the engine. */
    public suspend fun didDeactivateFromTelecom() {
        activation = Activation.INACTIVE
        tellEngineAndWait { audioSessionDidDeactivate() }
    }

    /**
     * Stops observing devices, gives up audio focus and stops telling the engine about changes.
     * Before Android 12 it also stops a Bluetooth SCO link [selectOutput] started. Do not use the
     * controller afterwards.
     */
    public fun release() {
        engineUpdates.cancel()
        scope.cancel()
        if (scoReceiver != null) stopSco()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnCommunicationDeviceChangedListener(
                communicationDeviceListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Routes audio to the loudspeaker or back to the default route. */
    public fun setSpeakerEnabled(enabled: Boolean) {
        if (enabled) selectOutput(BaresipAudioOutput.SPEAKER) else clearCommunicationDevice()
    }

    /** The current output route. */
    public val currentRoute: BaresipAudioRoute
        get() = currentDevice()?.let { audioRouteOf(it.type) } ?: BaresipAudioRoute.Other(0)

    /**
     * The outputs the user can pick for call audio right now: the earpiece (unless headphones are
     * plugged in, which replace it), the loudspeaker, and each headset or car kit.
     */
    public val availableOutputs: List<BaresipAudioOutput>
        get() = AudioOutputs.available(communicationDevices().map(::snapshot))

    /** The output call audio is playing on, as listed by [availableOutputs]. */
    public val currentOutput: BaresipAudioOutput?
        get() = AudioOutputs.current(currentDevice()?.let(::snapshot), availableOutputs)

    /**
     * Plays call audio on the given output, one of [availableOutputs].
     *
     * Before Android 12 a Bluetooth output starts a SCO link, and the route becomes Bluetooth
     * once the headset has connected it; [onRouteChange] is called then.
     */
    public fun selectOutput(output: BaresipAudioOutput) {
        val devices = communicationDevices()
        val device =
            when (output.id) {
                BaresipAudioOutput.SPEAKER.id -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                BaresipAudioOutput.EARPIECE.id -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                else -> devices.firstOrNull { it.id.toString() == output.id }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (device != null) audioManager.setCommunicationDevice(device) else audioManager.clearCommunicationDevice()
            return
        }
        @Suppress("DEPRECATION")
        when (output.route) {
            BaresipAudioRoute.Speaker -> {
                stopSco()
                audioManager.isSpeakerphoneOn = true
            }
            BaresipAudioRoute.Bluetooth -> {
                audioManager.isSpeakerphoneOn = false
                startSco()
            }
            else -> {
                stopSco()
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startSco() {
        if (scoReceiver == null) {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (isInitialStickyBroadcast) return
                        scoStateChanged(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR))
                    }
                }
            scoReceiver = receiver
            appContext.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), null, handler)
        }
        audioManager.startBluetoothSco()
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        unregisterScoReceiver()
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
    }

    @Suppress("DEPRECATION")
    private fun scoStateChanged(state: Int) {
        if (scoReceiver == null) return
        when (scoLinkResponse(state)) {
            ScoLink.UP -> audioManager.isBluetoothScoOn = true
            ScoLink.DOWN -> stopSco()
            ScoLink.PENDING -> return
        }
        onRouteChange?.invoke(currentRoute)
    }

    private fun unregisterScoReceiver() {
        val receiver = scoReceiver ?: return
        scoReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    private fun tellEngine(update: suspend BaresipEngine.() -> Unit) {
        engineUpdates.trySend(EngineUpdate(update))
    }

    private suspend fun tellEngineAndWait(update: suspend BaresipEngine.() -> Unit) {
        val done = CompletableDeferred<Unit>()
        if (engineUpdates.trySend(EngineUpdate(update, done)).isSuccess) done.await()
    }

    private fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    private fun communicationDevices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    @Suppress("DEPRECATION")
    private fun currentDevice(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice
        } else {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val wanted =
                when {
                    audioManager.isBluetoothScoOn -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    audioManager.isSpeakerphoneOn -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    outputs.any { audioRouteOf(it.type) == BaresipAudioRoute.WiredHeadset } ->
                        outputs.first { audioRouteOf(it.type) == BaresipAudioRoute.WiredHeadset }.type
                    else -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            outputs.firstOrNull { it.type == wanted }
        }

    private fun snapshot(device: AudioDeviceInfo) = AudioDevice(device.id, device.type, device.productName?.toString().orEmpty())

    private fun devicesChanged(devices: Array<out AudioDeviceInfo>) {
        if (!restartsAudio(devices.map { it.type })) return
        tellEngine { audioRouteDidChange() }
        onRouteChange?.invoke(currentRoute)
    }

    internal companion object {
        fun scoLinkResponse(state: Int): ScoLink =
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> ScoLink.UP
                AudioManager.SCO_AUDIO_STATE_CONNECTING -> ScoLink.PENDING
                else -> ScoLink.DOWN
            }

        fun focusResponse(
            change: Int,
            activation: Activation,
        ): FocusResponse =
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusResponse.PAUSE_ENGINE
                AudioManager.AUDIOFOCUS_GAIN ->
                    if (activation == Activation.CONTROLLER) FocusResponse.RESUME_ENGINE else FocusResponse.STAY
                else -> FocusResponse.STAY
            }

        fun restartsAudio(types: List<Int>): Boolean =
            types.any {
                val route = audioRouteOf(it)
                route == BaresipAudioRoute.Bluetooth || route == BaresipAudioRoute.WiredHeadset
            }
    }
}
