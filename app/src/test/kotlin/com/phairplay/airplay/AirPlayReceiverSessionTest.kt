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
import java.net.InetAddress

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
}
