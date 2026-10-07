package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClientStreamingReportTest — the sender's once-per-second performance report.
 *
 * WHY: this used to be discarded unread. The fixture below is the real payload captured from
 * an iPhone (see docs/reference/streaming-report.md) rather than invented field names — the
 * first version of this parser guessed at "fps" and would have silently found nothing.
 */
class ClientStreamingReportTest {

    /** Verbatim field set from a live iOS mirroring session. */
    private fun liveReport(): ByteArray = PlistCodec.encode(
        mapOf(
            "encoderCurrentFPS" to 60,
            "beforeEncoderFPS" to 33,
            "submitSurfaceFPS" to 31,
            "idleEncoderFPS" to 2,
            "encoderDropFPS" to 0,
            "encoderQueueDropFPS" to 0,
            "sinkOverflowDropFPS" to 0,
            "idleDropFPS" to 0,
            "queuedFramesAvg" to 53,
            "sentFramesAvg" to 53,
            "lossAvg" to 0.004303562350978699,
            "rttAvg" to 9,
            "txUsageAvg" to 4659006.286449278,
            "txCapacityAvg" to 12326853.701055126
        )
    )

    @Test
    fun `reads the fields an iOS sender actually reports`() {
        val report = ClientStreamingReport.parse(liveReport())

        assertNotNull(report)
        assertEquals(60, report!!.encoderFps)
        assertEquals(31, report.submittedFps)
        assertEquals(33, report.beforeEncoderFps)
        assertEquals(53, report.queuedFrames)
        assertEquals(9.0, report.rttMs!!, 0.01)
        assertEquals(0.0043, report.lossAvg!!, 0.0001)
        assertEquals(4_659_006.29, report.txUsageAvg!!, 1.0)
        assertEquals(12_326_853.70, report.txCapacityAvg!!, 1.0)
        assertEquals(0, report.senderDroppedFps)
    }

    @Test
    fun `sums the sender's own drop counters`() {
        val report = ClientStreamingReport.parse(
            PlistCodec.encode(
                mapOf(
                    "encoderCurrentFPS" to 60,
                    "encoderDropFPS" to 3,
                    "encoderQueueDropFPS" to 1,
                    "sinkOverflowDropFPS" to 2,
                    "idleDropFPS" to 4
                )
            )
        )

        assertEquals(10, report!!.senderDroppedFps)
        assertTrue(report.describe().contains("senderDropped=10"))
    }

    /**
     * The locked-screen case. Without splitting the trailer off, the plist parse fails and the
     * whole report is lost — exactly when the sender's state is unusual.
     */
    @Test
    fun `parses a report that carries the locked-screen trailer`() {
        val withTrailer = liveReport() + ByteArray(ClientStreamingReport.LOCKED_SCREEN_TRAILER_SIZE)

        val report = ClientStreamingReport.parse(withTrailer)

        assertNotNull("trailer must not defeat the parse", report)
        assertEquals(60, report!!.encoderFps)
        assertEquals(ClientStreamingReport.LOCKED_SCREEN_TRAILER_SIZE, report.trailerBytes)
        assertTrue(report.describe().contains("screen locked"))
    }

    /** A sender that reports something new must have it noticed, not silently ignored. */
    @Test
    fun `flags keys it does not recognise and keeps them`() {
        val report = ClientStreamingReport.parse(
            PlistCodec.encode(mapOf("encoderCurrentFPS" to 60, "someFutureField" to 7))
        )

        assertNotNull(report)
        assertEquals(7L, (report!!.entries["someFutureField"] as Number).toLong())
        assertTrue(report.describe().contains("new=someFutureField"))
    }

    @Test
    fun `does not flag the known field set as new`() {
        val report = ClientStreamingReport.parse(liveReport())

        assertTrue("no field should be reported as new", !report!!.describe().contains("new="))
    }

    /** A malformed report must never take the mirror down with it. */
    @Test
    fun `returns null instead of throwing on garbage`() {
        assertNull(ClientStreamingReport.parse(ByteArray(0)))
        assertNull(ClientStreamingReport.parse(byteArrayOf(1, 2, 3, 4, 5)))
    }
}
