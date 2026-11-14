package io.github.mariokovacevic.baresipkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class EngineCancellationTest {
    @Before
    fun requireHostCore() = HostCore.require()

    private fun cancelOnNextLog(
        engine: BaresipEngine,
        job: AtomicReference<Job?>,
    ) {
        engine.setLogHandler { job.getAndSet(null)?.cancel() }
    }

    @Test
    fun aCallWhoseCallerWasCancelledIsHungUp() =
        runBlocking {
            val host =
                java.net.NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .first { it is java.net.Inet4Address }
                    .hostAddress
            val port = DatagramSocket(0).use { it.localPort }
            val unused = DatagramSocket(0).use { it.localPort }
            val configuration =
                HostCore.configuration().copy {
                    extraConfiguration = "sip_listen\t\t0.0.0.0:$port"
                    ipv6 = false
                }
            BaresipEngine(configuration).use { engine ->
                engine.start()
                engine.addAccount(
                    BaresipAccountConfiguration(aor = "sip:alice@$host:$port") {
                        transport = BaresipTransport.UDP
                        mediaEncryption = BaresipMediaEncryption.NONE
                    },
                )
                val target = AtomicReference<Job?>(null)
                val placing =
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        engine.makeCall(destination = "sip:bob@$host:$unused")
                    }
                target.set(placing)
                cancelOnNextLog(engine, target)
                placing.start()
                val outcome = runCatching { placing.await() }.exceptionOrNull()
                engine.setLogHandler(null)
                assertTrue("makeCall was not cancelled: $outcome", outcome is CancellationException)
                val calls =
                    withTimeoutOrNull(10.seconds) {
                        while (engine.activeCalls().isNotEmpty()) delay(50)
                        emptyList<BaresipCallID>()
                    } ?: engine.activeCalls()
                assertEquals(emptyList<BaresipCallID>(), calls)
                engine.stop()
            }
        }

    @Test
    fun anAccountWhoseCallerWasCancelledIsRemoved() =
        runBlocking {
            BaresipEngine(HostCore.configuration()).use { engine ->
                engine.start()
                val target = AtomicReference<Job?>(null)
                val adding =
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        engine.addAccount(BaresipAccountConfiguration(aor = "sip:alice@example.invalid"))
                    }
                target.set(adding)
                cancelOnNextLog(engine, target)
                adding.start()
                val outcome = runCatching { adding.await() }.exceptionOrNull()
                engine.setLogHandler(null)
                assertTrue("addAccount was not cancelled: $outcome", outcome is CancellationException)
                assertEquals(emptyMap<BaresipAccountID, BaresipRegistrationState>(), engine.accounts())
                engine.stop()
            }
        }

    @Test
    fun gettersAreSafeWhileTheEngineCloses() =
        runBlocking {
            repeat(20) {
                val engine = BaresipEngine(HostCore.configuration())
                engine.start()
                val stop = AtomicBoolean(false)
                val readers =
                    List(4) {
                        launch(Dispatchers.Default) {
                            while (!stop.get()) {
                                engine.state
                                engine.registrationState(BaresipAccountID(1))
                                engine.isEngineThread
                            }
                        }
                    }
                delay(5)
                engine.close()
                stop.set(true)
                withTimeout(5.seconds) { readers.forEach { it.join() } }
                assertEquals(BaresipEngineState.STOPPED, engine.state)
                assertEquals(BaresipRegistrationState.NONE, engine.registrationState(BaresipAccountID(1)))
                assertFalse(engine.isEngineThread)
            }
        }

    @Test
    fun aCancelledListingThrowsTheCancellation() =
        runBlocking {
            BaresipEngine(HostCore.configuration()).use { engine ->
                engine.start()
                for (listing in listOf<suspend () -> Any>({ engine.accounts() }, { engine.activeCalls() }, { engine.videoDevices() })) {
                    val continued = AtomicBoolean(false)
                    launch(Dispatchers.Default) {
                        coroutineContext.job.cancel()
                        listing()
                        continued.set(true)
                    }.join()
                    assertFalse(continued.get())
                }
                engine.close()
                assertEquals(emptyMap<BaresipAccountID, BaresipRegistrationState>(), engine.accounts())
                assertEquals(emptyList<BaresipCallID>(), engine.activeCalls())
                assertEquals(emptyList<String>(), engine.videoDevices())
            }
        }
}
