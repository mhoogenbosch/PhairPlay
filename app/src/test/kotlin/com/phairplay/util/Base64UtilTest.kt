package com.phairplay.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Verifies the pure-Kotlin Base64 codec used for SDP/key decoding (replaces android.util.Base64). */
class Base64UtilTest {

    @Test
    fun `decodes known RFC 4648 vectors`() {
        assertEquals("", String(Base64Util.decode("")))
        assertEquals("f", String(Base64Util.decode("Zg==")))
        assertEquals("fo", String(Base64Util.decode("Zm8=")))
        assertEquals("foo", String(Base64Util.decode("Zm9v")))
        assertEquals("foobar", String(Base64Util.decode("Zm9vYmFy")))
    }

    @Test
    fun `decodes a 16-byte key like an SDP aeskey`() {
        val out = Base64Util.decode("MTIzNDU2Nzg5MDEyMzQ1Ng==")
        assertEquals(16, out.size)
        assertEquals("1234567890123456", String(out))
    }

    @Test
    fun `tolerates embedded whitespace (line-wrapped PEM or SDP)`() {
        assertEquals("foobar", String(Base64Util.decode("Zm9v\r\nYmFy")))
        assertEquals("foobar", String(Base64Util.decode("Zm9v YmFy")))
    }

    @Test
    fun `round-trips arbitrary bytes through encode then decode`() {
        for (len in 0..32) {
            val data = ByteArray(len) { (it * 31 + 7).toByte() }
            assertArrayEquals(data, Base64Util.decode(Base64Util.encode(data)))
        }
    }

    @Test
    fun `throws on invalid characters`() {
        assertThrows(IllegalArgumentException::class.java) { Base64Util.decode("!!!not base64!!!") }
    }
}
