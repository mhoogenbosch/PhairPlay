package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** FLUSH carries `RTP-Info: seq=<n>;rtptime=<t>`; the seq anchors the audio after a skip/seek. */
class RtpInfoSeqTest {
    @Test
    fun `reads seq from RTP-Info`() {
        assertEquals(12345, parseRtpInfoSeq("seq=12345;rtptime=987654"))
        assertEquals(7, parseRtpInfoSeq("rtptime=1; seq=7"))
    }

    @Test
    fun `absent or malformed gives -1`() {
        assertEquals(-1, parseRtpInfoSeq(null))
        assertEquals(-1, parseRtpInfoSeq("rtptime=1"))
        assertEquals(-1, parseRtpInfoSeq("seq=abc"))
        assertEquals(-1, parseRtpInfoSeq("seq=70000"))
    }
}
