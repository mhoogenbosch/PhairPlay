package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirPlayReceiverSessionTest — session lifecycle of the receiver (mh.15).
 *
 * WHY: until mh.15 the teardown guard was only re-armed by the legacy RECORD path, so from the
 * second mirror / AirPlay 2 audio session after a service start the session end was a no-op: the UI
 * stayed on CONNECTED and each session's NTP client + event socket leaked. These tests drive two
 * consecutive sessions of each kind and check that both end in ADVERTISING with nothing left over,
 * that removing the last stream keeps the session keys, and that mDNS can't overwrite CONNECTED.
 */
class AirPlayReceiverSessionTest {

    private val states = mutableListOf<ProtocolState>()
    private lateinit var receiver: AirPlayReceiver
    private val sender = InetAddress.getLoopbackAddress()

    @Before
    fun setup() {
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NSD_SERVICE) } returns mockk<NsdManager>(relaxed = true)
        receiver = AirPlayReceiver(
            context = context,
            audioEnabled = true,
            videoSurfaceProvider = { null },
            onStateChanged = { synchronized(states) { states.add(it) } },
            stateDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        receiver.stop()
    }

    private fun snapshot() = synchronized(states) { states.toList() }

    @Test
    fun `two consecutive mirror sessions both end in ADVERTISING without leftovers`() {
        repeat(2) { round ->
            receiver.startMirrorKeysForTest(sender, 9, "iPhone")
            receiver.startMirrorStreamForTest()
            assertTrue("session $round must be active", receiver.isSessionActive)
            assertTrue(receiver.hasNtpClient)
            assertTrue(receiver.hasEventSocket)

            receiver.onStreamingStoppedForTest(keepKeys = false)

            assertEquals("session $round must end in ADVERTISING", ProtocolState.ADVERTISING, snapshot().last())
            assertFalse("NTP client of session $round leaked", receiver.hasNtpClient)
            assertFalse("event socket of session $round leaked", receiver.hasEventSocket)
            assertFalse(receiver.isSessionActive)
        }
        assertEquals(
            listOf(ProtocolState.CONNECTED, ProtocolState.ADVERTISING, ProtocolState.CONNECTED, ProtocolState.ADVERTISING),
            snapshot()
        )
    }

    @Test
    fun `two consecutive AirPlay 2 audio sessions both end in ADVERTISING without leftovers`() {
        repeat(2) { round ->
            receiver.startMirrorKeysForTest(sender, 9, "iPhone")
            receiver.startMirrorAudioForTest(AudioStreamServer.CT_AAC_LC)
            receiver.onStreamingStoppedForTest(keepKeys = false)

            assertEquals("audio session $round must end in ADVERTISING", ProtocolState.ADVERTISING, snapshot().last())
            assertFalse(receiver.hasNtpClient)
            assertFalse(receiver.hasEventSocket)
        }
        assertEquals(4, snapshot().size)
    }

    @Test
    fun `a duplicate teardown (TEARDOWN then socket close) emits ADVERTISING once`() {
        receiver.startMirrorKeysForTest(sender, 9, "iPhone")
        receiver.onStreamingStoppedForTest(keepKeys = false)
        receiver.onStreamingStoppedForTest(keepKeys = false)
        assertEquals(listOf(ProtocolState.CONNECTED, ProtocolState.ADVERTISING), snapshot())
    }

    @Test
    fun `removing the last stream keeps the keys so the stream can be re-added`() {
        receiver.startMirrorKeysForTest(sender, 9, "iPhone")
        receiver.startMirrorStreamForTest()
        receiver.onStreamingStoppedForTest(keepKeys = true)   // stream-level TEARDOWN of the last stream

        assertEquals(ProtocolState.ADVERTISING, snapshot().last())
        assertTrue("keys must survive the last-stream teardown", receiver.hasSessionKeys)
        assertTrue("NTP must survive the last-stream teardown", receiver.hasNtpClient)

        // Re-SETUP of stream 110 on the same connection: works and brings the session back.
        receiver.startMirrorStreamForTest()
        assertTrue(receiver.isSessionActive)
        assertEquals(ProtocolState.CONNECTED, snapshot().last())

        // Control connection closes → full teardown.
        receiver.onStreamingStoppedForTest(keepKeys = false)
        assertFalse(receiver.hasSessionKeys)
        assertFalse(receiver.hasNtpClient)
        assertEquals(ProtocolState.ADVERTISING, snapshot().last())
    }

    @Test
    fun `mDNS states never overwrite CONNECTED while a session runs`() {
        receiver.startMirrorKeysForTest(sender, 9, "iPhone")
        receiver.emitMdnsStateForTest(ProtocolState.DISABLED)
        receiver.emitMdnsStateForTest(ProtocolState.ADVERTISING)
        assertEquals(listOf(ProtocolState.CONNECTED), snapshot())

        receiver.onStreamingStoppedForTest(keepKeys = false)
        receiver.emitMdnsStateForTest(ProtocolState.DISABLED)   // the per-session mDNS restart
        assertEquals(
            listOf(ProtocolState.CONNECTED, ProtocolState.ADVERTISING, ProtocolState.DISABLED),
            snapshot()
        )
    }

    /**
     * A fake iPhone timing port on loopback: counts the receiver's NTP requests and answers them
     * while [answering] is true. When the iPhone ends a session it stops answering on that port.
     */
    private class FakeSender : AutoCloseable {
        val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val requests = AtomicInteger()
        val answering = AtomicBoolean(true)
        private val thread = Thread {
            val buf = ByteArray(128)
            while (!socket.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    requests.incrementAndGet()
                    if (answering.get()) {
                        val reply = ByteArray(32).also { it[0] = 0x80.toByte(); it[1] = 0xD3.toByte() }
                        socket.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        val port: Int get() = socket.localPort
        override fun close() = socket.close()
    }

    /**
     * Replays #19 log6 (mh.18 on a Sony): the first mirror session after a service start ran fine,
     * every later one was dropped after 6-23 s with "no timing reply from the sender for 32s",
     * logged 0.15 s after a reply HAD arrived. The silent one was the previous session's NTP client:
     * its teardown was a no-op, the client kept polling a port the iPhone had closed, and when it
     * gave up it dropped whatever session was current. Here: two real NTP clients against two fake
     * senders, with a 1.5 s silence timeout standing in for 30 s.
     */
    @Test
    fun `a finished session's NTP client cannot drop the next session`() {
        receiver.ntpSilenceTimeoutMs = 1_500
        receiver.ntpPollIntervalMs = 100
        FakeSender().use { first ->
            FakeSender().use { second ->
                receiver.startMirrorKeysForTest(sender, first.port, "iPhone")
                receiver.startMirrorStreamForTest()
                Thread.sleep(400)
                assertTrue("session 1 must poll its sender", first.requests.get() > 0)

                receiver.onStreamingStoppedForTest(keepKeys = false)   // iPhone: Stop Mirroring
                first.answering.set(false)                              // ...and that port goes quiet
                Thread.sleep(300)
                val firstAfterStop = first.requests.get()

                receiver.startMirrorKeysForTest(sender, second.port, "iPhone")
                receiver.startMirrorStreamForTest()
                Thread.sleep(2_500)                                     // well past the silence timeout

                assertEquals("no session may be dropped for NTP silence", 0, receiver.ntpSilenceDrops.get())
                assertTrue("session 2 must still be active", receiver.isSessionActive)
                assertEquals("session 1's NTP client kept polling after its teardown",
                    firstAfterStop, first.requests.get())
                assertTrue("session 2 must poll its own sender", second.requests.get() > 10)
                assertEquals(ProtocolState.CONNECTED, snapshot().last())
            }
        }
    }

    /** The mh.16 detector itself still works: a sender that really goes quiet is dropped once. */
    @Test
    fun `a sender that really stops answering is detected once`() {
        receiver.ntpSilenceTimeoutMs = 1_000
        receiver.ntpPollIntervalMs = 100
        FakeSender().use { s ->
            receiver.startMirrorKeysForTest(sender, s.port, "iPhone")
            Thread.sleep(300)
            s.answering.set(false)
            Thread.sleep(2_000)
            assertEquals(1, receiver.ntpSilenceDrops.get())
        }
    }
}
