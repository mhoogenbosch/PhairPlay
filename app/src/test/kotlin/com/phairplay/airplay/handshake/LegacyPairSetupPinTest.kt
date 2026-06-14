package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Validates [LegacyPairSetupPin] (the SRP server) against a reference SRP **client** implemented
 * straight from openairplay/AirPlayAuth's Apple routines (ClientEvidenceRoutineImpl + the two-counter
 * session key + XRoutineWithUserIdentity). SRP is self-checking: the client derives the shared secret
 * S as `(B − k·g^x)^(a+u·x)` while the server uses `(A·v^u)^b`; these only converge if k, u, x, and the
 * padding all match — so a passing handshake here means the byte-level crypto matches what macOS sends.
 */
class LegacyPairSetupPinTest {

    @Test
    fun `full SRP + AES-GCM handshake against a reference Apple client succeeds`() {
        val pin = "4271"
        val user = "52:C7:B3:75:A2:D7"
        val serverEdPublic = ByteArray(32) { it.toByte() }
        val server = LegacyPairSetupPin(pin, serverEdPublic)

        // ── step 1 → salt, B ─────────────────────────────────────────────────
        val r1 = server.handle(mapOf("method" to "pin", "user" to user))
        val salt = r1.reply!!["salt"] as ByteArray
        val b = BigInteger(1, r1.reply!!["pk"] as ByteArray)

        // ── step 2: client M1 proof → server M2 ──────────────────────────────
        val client = RefClient(user, pin, salt, b)
        val r2 = server.handle(mapOf("pk" to toBytes(client.a), "proof" to client.m1))
        assertTrue("server must accept the reference client's M1 proof", !r2.failed && r2.reply != null)
        assertArrayEquals("server M2 must match", client.m2, r2.reply!!["proof"] as ByteArray)

        // ── step 3: AES-GCM key exchange ─────────────────────────────────────
        val aesKey = sha512("Pair-Setup-AES-Key".toByteArray(), client.k).copyOf(16)
        val aesIv = sha512("Pair-Setup-AES-IV".toByteArray(), client.k).copyOf(16)
        incrementIv(aesIv)
        val clientEdPublic = ByteArray(32) { (100 + it).toByte() }
        val clientEpk = gcm(true, aesKey, aesIv, clientEdPublic)!!
        val r3 = server.handle(mapOf(
            "epk" to clientEpk.copyOf(clientEpk.size - 16),
            "authTag" to clientEpk.copyOfRange(clientEpk.size - 16, clientEpk.size),
        ))
        assertTrue("server must complete after the AES key exchange", r3.complete)
        // Server reply is encrypted at IV+2 (per-message counter) — decrypt with the next IV.
        incrementIv(aesIv)
        val serverEpk = (r3.reply!!["epk"] as ByteArray) + (r3.reply!!["authTag"] as ByteArray)
        assertArrayEquals("decrypted accessory key must match", serverEdPublic, gcm(false, aesKey, aesIv, serverEpk))
    }

    @Test
    fun `wrong PIN is rejected`() {
        val server = LegacyPairSetupPin("4271", ByteArray(32))
        val r1 = server.handle(mapOf("method" to "pin", "user" to "u"))
        val salt = r1.reply!!["salt"] as ByteArray
        val b = BigInteger(1, r1.reply!!["pk"] as ByteArray)
        val client = RefClient("u", "0000", salt, b)   // wrong PIN
        val r2 = server.handle(mapOf("pk" to toBytes(client.a), "proof" to client.m1))
        assertTrue("server must reject a wrong-PIN proof", r2.failed)
    }

    /** Reference SRP-6a client matching Apple's legacy AirPlay routines. */
    private class RefClient(user: String, pin: String, salt: ByteArray, b: BigInteger) {
        val a: BigInteger
        val m1: ByteArray
        val m2: ByteArray
        val k: ByteArray   // 40-byte SRP session key

        init {
            val aPriv = BigInteger(256, SecureRandom())
            a = G.modPow(aPriv, N)
            val x = BigInteger(1, sha1(salt, sha1("$user:$pin".toByteArray())))
            val kMul = BigInteger(1, sha1(pad(N), pad(G)))
            val u = BigInteger(1, sha1(pad(a), pad(b)))
            // client secret: S = (B − k·g^x)^(a + u·x)
            val s = b.subtract(kMul.multiply(G.modPow(x, N))).mod(N).modPow(aPriv.add(u.multiply(x)), N)
            k = sessionKey(toBytes(s))
            val hNxorHg = sha1(toBytes(N)).let { hn ->
                val hg = sha1(toBytes(G)); ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            }
            m1 = sha1(hNxorHg, sha1(user.toByteArray()), salt, toBytes(a), toBytes(b), k)
            m2 = sha1(toBytes(a), m1, k)
        }
    }

    companion object {
        private const val N_BYTES = 256
        private val G = BigInteger.valueOf(2)
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16
        )

        private fun sessionKey(sBytes: ByteArray): ByteArray =
            sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))

        private fun sha1(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        private fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        private fun incrementIv(iv: ByteArray) {
            for (i in iv.indices.reversed()) { iv[i] = (iv[i] + 1).toByte(); if (iv[i].toInt() != 0) break }
        }

        private fun gcm(encrypt: Boolean, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray? = try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                if (encrypt) javax.crypto.Cipher.ENCRYPT_MODE else javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv)
            )
            c.doFinal(input)
        } catch (_: Exception) { null }

        private fun toBytes(bi: BigInteger): ByteArray {
            val b = bi.toByteArray()
            return if (b.size > 1 && b[0].toInt() == 0) b.copyOfRange(1, b.size) else b
        }

        private fun pad(bi: BigInteger): ByteArray {
            val raw = toBytes(bi)
            if (raw.size >= N_BYTES) return raw
            return ByteArray(N_BYTES).also { System.arraycopy(raw, 0, it, N_BYTES - raw.size, raw.size) }
        }
    }
}
