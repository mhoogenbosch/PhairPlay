package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Verifies [DmapParser] against hand-built DMAP/DAAP TLV bodies like the ones macOS sends in a
 * binary `SET_PARAMETER` (now-playing metadata). Covers the `mlit`-wrapped form, the flat form,
 * UTF-8 payloads, and malformed/truncated input (must not throw or read out of bounds).
 */
class DmapParserTest {

    /** Encodes one DMAP tag: 4 ASCII bytes, 4-byte big-endian length, payload. */
    private fun tag(name: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(
            (payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ))
        out.write(payload)
        return out.toByteArray()
    }

    private fun str(name: String, value: String) = tag(name, value.toByteArray(Charsets.UTF_8))

    @Test
    fun `parses minm asar asal inside an mlit container`() {
        val children = str("minm", "Song Title") + str("asar", "The Artist") + str("asal", "The Album")
        val body = tag("mlit", children)

        val meta = DmapParser.parseNowPlaying(body)

        assertEquals("Song Title", meta.title)
        assertEquals("The Artist", meta.artist)
        assertEquals("The Album", meta.album)
    }

    @Test
    fun `parses flat tags with no container and ignores unknown tags`() {
        val body = str("minm", "Track") +
            tag("mikd", byteArrayOf(2)) +          // unknown/irrelevant tag — must be skipped
            str("asar", "Band")
        val meta = DmapParser.parseNowPlaying(body)

        assertEquals("Track", meta.title)
        assertEquals("Band", meta.artist)
        assertNull(meta.album)
    }

    @Test
    fun `decodes UTF-8 payloads`() {
        val meta = DmapParser.parseNowPlaying(tag("mlit", str("minm", "Café — naïve ♪")))
        assertEquals("Café — naïve ♪", meta.title)
    }

    @Test
    fun `truncated length does not throw and yields empty metadata`() {
        // 'minm' claims 99 bytes but only a few follow — must stop cleanly, not crash.
        val body = "minm".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x00, 0x00, 0x63) + "hi".toByteArray()
        val meta = DmapParser.parseNowPlaying(body)
        assertTrue(meta.isEmpty)
    }

    @Test
    fun `empty body yields empty metadata`() {
        assertTrue(DmapParser.parseNowPlaying(ByteArray(0)).isEmpty)
    }
}
