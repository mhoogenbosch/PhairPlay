package com.phairplay.airplay.handshake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirPlayNtpClientTest — the sender-liveness watchdog (#19).
 *
 * A sender that disappears without closing the control connection (TV into standby, phone off
 * the Wi-Fi) left the session open forever and every new sender got 503. The NTP client is the
 * one component that hears from the sender every couple of seconds, so it reports the silence.
 *
 * HOW: a loopback UDP socket plays the sender. One that never answers must trigger
 * onSenderSilent exactly once; one that answers every request must never trigger it.
 */
class AirPlayNtpClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val sender = DatagramSocket(0, loopback)
    private var client: AirPlayNtpClient? = null

    @After
    fun tearDown() {
        client?.stop()
        sender.close()
        scope.cancel()
    }

    @Test
    fun `silent sender triggers onSenderSilent once`() {
        val calls = AtomicInteger()
        val fired = CountDownLatch(1)
        client = AirPlayNtpClient(loopback, sender.localPort, silenceTimeoutMs = 300, pollIntervalMs = 50,
            onSenderSilent = { calls.incrementAndGet(); fired.countDown() }).also { it.start(scope) }

        assertTrue("watchdog did not fire", fired.await(5, TimeUnit.SECONDS))
        Thread.sleep(500) // a second call would land in this window
        assertEquals(1, calls.get())
    }

    @Test
    fun `answering sender never triggers onSenderSilent`() {
        val calls = AtomicInteger()
        client = AirPlayNtpClient(loopback, sender.localPort, silenceTimeoutMs = 400, pollIntervalMs = 50,
            onSenderSilent = { calls.incrementAndGet() }).also { it.start(scope) }

        // Echo every timing request back, like a live sender, for well past the timeout.
        sender.soTimeout = 200
        val buf = ByteArray(64)
        val until = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < until) {
            val rx = DatagramPacket(buf, buf.size)
            try { sender.receive(rx) } catch (_: java.net.SocketTimeoutException) { continue }
            sender.send(DatagramPacket(buf, 32, rx.address, rx.port))
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `stop before the timeout never triggers onSenderSilent`() {
        val calls = AtomicInteger()
        client = AirPlayNtpClient(loopback, sender.localPort, silenceTimeoutMs = 300, pollIntervalMs = 50,
            onSenderSilent = { calls.incrementAndGet() }).also { it.start(scope) }
        Thread.sleep(100)
        client?.stop()
        Thread.sleep(600)
        assertFalse(calls.get() > 0)
    }
}
