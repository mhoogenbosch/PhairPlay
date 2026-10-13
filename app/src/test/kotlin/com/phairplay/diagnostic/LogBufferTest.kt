package com.phairplay.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LogBufferTest — which lines reach the persistent file.
 *
 * WHY: during mirroring the `/feedback` keep-alive and the periodic stats filled the 5000-line
 * file in ~2 hours, so a standby night was gone by morning (#19). The live tail keeps everything;
 * the file must keep the events.
 */
class LogBufferTest {

    @Before
    fun reset() = LogBuffer.resetThrottle()

    @Test
    fun `ordinary lines always go to the file`() {
        repeat(3) { assertTrue(LogBuffer.fileDecision("Starting mDNS advertising as 'TV'", 1_000L)) }
        assertTrue(LogBuffer.fileDecision("RTSP SETUP rtsp://x/1", 1_000L))
    }

    @Test
    fun `feedback keep-alive never goes to the file`() {
        assertFalse(LogBuffer.fileDecision("RTSP POST /feedback", 0L))
        assertFalse(LogBuffer.fileDecision("RTSP POST /feedback", 600_000L))
    }

    @Test
    fun `stats are throttled per kind to one per minute`() {
        assertTrue(LogBuffer.fileDecision("Audio stats: recv=500", 0L))
        assertFalse(LogBuffer.fileDecision("Audio stats: recv=1000", 2_000L))
        assertTrue(LogBuffer.fileDecision("Video stats: in=300", 2_000L))   // other kind, own clock
        assertFalse(LogBuffer.fileDecision("Audio stats: recv=9000", 59_999L))
        assertTrue(LogBuffer.fileDecision("Audio stats: recv=9500", 60_000L))
        assertTrue(LogBuffer.fileDecision("Mirror: sender enc=60fps", 60_000L))
    }

    @Test
    fun `other Mirror lines are not throttled`() {
        assertTrue(LogBuffer.fileDecision("Mirror: resynced on keyframe after a dropped frame", 0L))
        assertTrue(LogBuffer.fileDecision("Mirror: resynced on keyframe after a dropped frame", 1L))
    }
}
