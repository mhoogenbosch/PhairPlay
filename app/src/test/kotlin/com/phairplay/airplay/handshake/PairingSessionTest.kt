package com.phairplay.airplay.handshake

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exercises the full pair-setup → pair-verify (M1, M2) exchange by simulating a macOS
 * client in-process, validating signature order, the SHA-512 key/IV derivation, and the
 * continuous AES-CTR keystream across M1/M2 — without needing a real device.
 */
class PairingSessionTest {

    @Test
    fun pairSetupReturnsServerEd25519PublicKey() {
        val server = PairingSession(PairingKeys.create(seed(1)))
        val pub = server.pairSetup(ByteArray(32))
        assertEquals(32, pub.size)
    }

    @Test
    fun pairVerifyRoundTripSucceeds() {
        val serverEdSeed = seed(7)
        val server = PairingSession(PairingKeys.create(serverEdSeed))
        val serverEdPublic = Ed25519PrivateKeyParameters(serverEdSeed, 0).generatePublicKey().encoded

        // --- client identity + ephemeral ECDH key ---
        val clientEd = Ed25519PrivateKeyParameters(seed(9), 0)
        val clientEdPublic = clientEd.generatePublicKey().encoded
        val clientEcdh = X25519PrivateKeyParameters(SecureRandom())
        val clientEcdhPublic = clientEcdh.generatePublicKey().encoded

        // --- M1: client → server ---
        val m1 = byteArrayOf(1, 0, 0, 0) + clientEcdhPublic + clientEdPublic
        val m1Resp = server.pairVerify(m1)
        assertEquals(96, m1Resp.size)
        val serverEcdhPublic = m1Resp.copyOfRange(0, 32)
        val encServerSig = m1Resp.copyOfRange(32, 96)

        // client derives the same shared secret + AES-CTR cipher
        val secret = ByteArray(32)
        X25519Agreement().apply { init(clientEcdh) }
            .calculateAgreement(X25519PublicKeyParameters(serverEcdhPublic, 0), secret, 0)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"),
                IvParameterSpec(derive("Pair-Verify-AES-IV", secret))
            )
        }

        // decrypt + verify the server's signature over (serverPub ‖ clientPub)
        val serverSig = cipher.update(encServerSig)
        assertTrue(
            "server signature must verify against its advertised Ed25519 key",
            ed25519Verify(serverEdPublic, serverEcdhPublic + clientEcdhPublic, serverSig)
        )

        // --- M2: client signs (clientPub ‖ serverPub), encrypts at keystream offset 64 ---
        val clientSig = ed25519Sign(clientEd, clientEcdhPublic + serverEcdhPublic)
        val encClientSig = cipher.update(clientSig)
        val m2 = byteArrayOf(0, 0, 0, 0) + encClientSig
        val m2Resp = server.pairVerify(m2)
        assertEquals(0, m2Resp.size)   // empty body = pairing verified
    }

    private fun seed(b: Int) = ByteArray(32) { b.toByte() }

    private fun derive(salt: String, secret: ByteArray) =
        MessageDigest.getInstance("SHA-512")
            .digest(salt.toByteArray(Charsets.US_ASCII) + secret).copyOf(16)

    private fun ed25519Sign(key: Ed25519PrivateKeyParameters, msg: ByteArray) =
        Ed25519Signer().run { init(true, key); update(msg, 0, msg.size); generateSignature() }

    private fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray) =
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(pub, 0)); update(msg, 0, msg.size); verifySignature(sig)
        }
}
