package org.ghostsinthelab.app.ringomote.network.companion

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** The primitives HAP pairing needs, over Bouncy Castle's lightweight API. */
object HapCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** HKDF-SHA512 with string salt and info, 32 bytes out, as HAP specifies. */
    fun hkdf(salt: String, info: String, secret: ByteArray): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
        return ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
    }

    /**
     * ChaCha20-Poly1305 with a 12-byte nonce; a shorter [nonce] is left-padded with zeros, so
     * HAP's ASCII nonces (`PS-Msg05`) and little-endian counters both work. The 16-byte tag is
     * appended to the ciphertext.
     */
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        aead(true, key, nonce, plaintext, aad)

    /** Inverse of [encrypt]; throws [org.bouncycastle.crypto.InvalidCipherTextException] on a bad tag. */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray =
        aead(false, key, nonce, ciphertext, aad)

    private fun aead(forEncryption: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(KeyParameter(key), 128, padNonce(nonce), aad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val len = cipher.processBytes(input, 0, input.size, out, 0)
        val total = len + cipher.doFinal(out, len)
        return if (total == out.size) out else out.copyOf(total)
    }

    fun padNonce(nonce: ByteArray): ByteArray =
        if (nonce.size >= 12) nonce else ByteArray(12 - nonce.size) + nonce

    /** A counter nonce: [counter] little endian in 12 bytes, as the Companion session uses. */
    fun counterNonce(counter: Long): ByteArray = ByteArray(12) { i ->
        if (i < 8) ((counter ushr (8 * i)) and 0xFF).toByte() else 0
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    fun x25519PublicKey(privateKey: ByteArray): ByteArray = X25519PrivateKeyParameters(privateKey).generatePublicKey().encoded

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey))
        return ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey), it, 0)
        }
    }
}
