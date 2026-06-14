package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher

/**
 * Verifies the legacy `rsaaeskey` recovery: a 16-byte key encrypted with the embedded AirPort
 * Express *public* key (RSA-2048, OAEP/SHA-1) must decrypt back to the same bytes via [RaopRsa].
 * This proves the embedded PKCS#8 key loads and the OAEP plumbing matches what real senders use,
 * without needing an actual legacy AirPlay sender. Pure JCA — no native lib, no Android Base64.
 */
class RaopRsaTest {

    private fun encryptWithEmbeddedPublicKey(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, RaopRsa.publicKeyForTest())
        return cipher.doFinal(plain)
    }

    @Test
    fun `round-trips a 16-byte AES key through the embedded AirPort Express key`() {
        val key = ByteArray(16) { (it * 7 + 1).toByte() }
        val blob = encryptWithEmbeddedPublicKey(key)
        assertEquals("RSA-2048 ciphertext is 256 bytes", 256, blob.size)
        assertArrayEquals(key, RaopRsa.decryptAesKey(blob))
    }

    @Test
    fun `malformed blob returns null rather than throwing`() {
        assertNull(RaopRsa.decryptAesKey(ByteArray(256) { 0x5A }))
    }
}
