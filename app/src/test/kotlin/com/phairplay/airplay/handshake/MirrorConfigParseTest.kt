package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirror type-1 config (avcC) parsing: valid records parse, malformed ones return null, never throw. */
class MirrorConfigParseTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    private fun avcC(spsLen: Int = sps.size, ppsLen: Int = pps.size): ByteArray =
        byteArrayOf(1, 0x64, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), (spsLen ushr 8).toByte(), spsLen.toByte()) +
            sps + byteArrayOf(1, (ppsLen ushr 8).toByte(), ppsLen.toByte()) + pps

    @Test
    fun `parses SPS and PPS from a well-formed record`() {
        val c = parseAvcC(avcC())!!
        assertArrayEquals(sps, c.sps)
        assertArrayEquals(pps, c.pps)
    }

    @Test
    fun `rejects lengths that run past the end`() {
        assertNull(parseAvcC(avcC(spsLen = 200)))
        assertNull(parseAvcC(avcC(ppsLen = 200)))
        assertNull(parseAvcC(avcC(spsLen = 0)))
    }

    @Test
    fun `rejects truncated and HEVC headers`() {
        assertNull(parseAvcC(ByteArray(0)))
        assertNull(parseAvcC(avcC().copyOf(10)))
        assertNull(parseAvcC(byteArrayOf(0, 0, 0, 0, 'h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte(), 0, 0, 0, 0)))
    }

    @Test
    fun `avccToAnnexB stops at a length prefix that would overflow`() {
        // 4-byte NAL of length 2, then a corrupt prefix 0x7FFFFFFF that `i + len` would overflow.
        val data = byteArrayOf(0, 0, 0, 2, 0x41, 0x01, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)
        val out = MirrorCrypto.avccToAnnexB(data)
        assertEquals(6, out.size)                                    // start code + the one valid NAL
    }
}
