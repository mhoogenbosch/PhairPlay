package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the 24-byte big-endian ALACSpecificConfig "magic cookie" the native ALAC decoder's
 * Init() parses (ALACAudioTypes.h struct layout). The native side reads the struct big-endian via
 * Swap*BtoN, so getting the byte order or field offsets wrong silently misparses every field
 * (e.g. a huge frameLength → calloc failure). These are pure-logic tests — no native lib loaded.
 */
class AlacDecoderTest {

    @Test
    fun `magic cookie for 44_100 Hz stereo spf 352 matches the canonical AirPlay layout`() {
        // frameLength=352, compatVer=0, bitDepth=16, pb=40, mb=10, kb=14, channels=2,
        // maxRun=255, maxFrameBytes=0, avgBitRate=0, sampleRate=44100 — all big-endian.
        val expected = byteArrayOf(
            0x00, 0x00, 0x01, 0x60,             // frameLength = 352
            0x00,                               // compatibleVersion
            0x10,                               // bitDepth = 16
            0x28,                               // pb = 40
            0x0A,                               // mb = 10
            0x0E,                               // kb = 14
            0x02,                               // numChannels = 2
            0x00, 0xFF.toByte(),                // maxRun = 255
            0x00, 0x00, 0x00, 0x00,             // maxFrameBytes = 0
            0x00, 0x00, 0x00, 0x00,             // avgBitRate = 0
            0x00, 0x00, 0xAC.toByte(), 0x44,    // sampleRate = 44100
        )
        assertArrayEquals(expected, AlacDecoder.buildMagicCookie(44100, 2, 352))
    }

    @Test
    fun `magic cookie is exactly 24 bytes (sizeof ALACSpecificConfig)`() {
        assertEquals(24, AlacDecoder.buildMagicCookie(48000, 1, 4096).size)
    }

    @Test
    fun `magic cookie encodes 48 kHz mono with a different frame length`() {
        val cookie = AlacDecoder.buildMagicCookie(48000, 1, 4096)
        // frameLength = 4096 = 0x00001000 (BE32 at offset 0)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x10, 0x00), cookie.copyOfRange(0, 4))
        assertEquals(1, cookie[9].toInt())                       // numChannels
        // sampleRate = 48000 = 0x0000BB80 (BE32 at offset 20)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0xBB.toByte(), 0x80.toByte()), cookie.copyOfRange(20, 24))
    }
}
