package org.ghostsinthelab.app.ringomote.network.companion

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The SRP-6a client side of HAP pair-setup: RFC 5054's 3072-bit group, generator 5,
 * SHA-512, username `Pair-Setup`, the PIN shown on the Apple TV as the password.
 *
 * Byte conventions follow the reference client the Apple TV accepts: `k = H(N | pad(g))`,
 * `u = H(pad(A) | pad(B))` with values padded to the length of N, while `A`, `B` and `S`
 * are hashed as minimal big-endian integers; `x = H(salt | H("Pair-Setup:" + PIN))`,
 * `K = H(S)`, `M1 = H(H(N) xor H(g) | H("Pair-Setup") | salt | A | B | K)`,
 * `M2 = H(A | M1 | K)`. The private exponent [a] is supplied so that tests are repeatable.
 */
class HapSrpClient(private val pin: String, a: ByteArray) {
    private val a: BigInteger = BigInteger(1, a)

    /** The client public value `A`, minimal big-endian bytes. */
    val publicKey: ByteArray

    private val k: BigInteger = hashInt(N_BYTES, pad(G))

    private var sessionKey: ByteArray? = null
    private var clientProof: ByteArray? = null
    private var expectedServerProof: ByteArray? = null

    init {
        publicKey = G.modPow(this.a, N).toMinimalBytes()
    }

    /**
     * Processes the accessory's `salt` and public value `B` (from M2) and returns the client
     * proof `M1` for M3. Throws [IllegalArgumentException] for a `B` that is zero mod N.
     */
    fun process(salt: ByteArray, serverPublic: ByteArray): ByteArray {
        val b = BigInteger(1, serverPublic)
        require(b.mod(N).signum() != 0) { "Invalid server public value" }
        val aPub = BigInteger(1, publicKey)
        val u = hashInt(pad(aPub), pad(b))
        val x = hashInt(salt, hash((USERNAME + ":" + pin).toByteArray()))
        val v = G.modPow(x, N)
        val s = b.subtract(k.multiply(v)).mod(N).modPow(a.add(u.multiply(x)), N)
        val key = hash(s.toMinimalBytes())
        val m1 = hash(
            xor(hash(N_BYTES), hash(G.toMinimalBytes())),
            hash(USERNAME.toByteArray()),
            salt,
            publicKey,
            b.toMinimalBytes(),
            key,
        )
        sessionKey = key
        clientProof = m1
        expectedServerProof = hash(publicKey, m1, key)
        return m1
    }

    /** True if the accessory's proof `M2` (from M4) matches; call after [process]. */
    fun verifyServerProof(proof: ByteArray): Boolean =
        expectedServerProof?.let { MessageDigest.isEqual(it, proof) } ?: false

    /** The shared session key `K` (64 bytes); valid after [process]. */
    val key: ByteArray get() = checkNotNull(sessionKey) { "process() not called" }

    private fun pad(value: BigInteger): ByteArray {
        val bytes = value.toMinimalBytes()
        return ByteArray(N_BYTES.size - bytes.size) + bytes
    }

    private fun hashInt(vararg parts: ByteArray) = BigInteger(1, hash(*parts))

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    companion object {
        const val USERNAME = "Pair-Setup"

        private val G: BigInteger = BigInteger.valueOf(5)
        private val N: BigInteger = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val N_BYTES: ByteArray = N.toMinimalBytes()

        private fun hash(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach(md::update)
            return md.digest()
        }

        /** Big-endian magnitude without a sign byte or leading zeros (zero becomes one zero byte). */
        private fun BigInteger.toMinimalBytes(): ByteArray {
            val bytes = toByteArray()
            val start = bytes.indexOfFirst { it != 0.toByte() }
            return when {
                start < 0 -> byteArrayOf(0)
                start == 0 -> bytes
                else -> bytes.copyOfRange(start, bytes.size)
            }
        }
    }
}
