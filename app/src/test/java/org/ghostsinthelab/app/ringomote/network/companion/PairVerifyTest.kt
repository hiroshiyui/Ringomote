package org.ghostsinthelab.app.ringomote.network.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pair-verify against a simulated accessory holding the other half of the pairing. */
class PairVerifyTest {
    private val accessorySeed = ByteArray(32) { (it + 1).toByte() }
    private val controllerSeed = ByteArray(32) { (it + 100).toByte() }
    private val accessoryId = "AA:BB:CC:DD:EE:FF".toByteArray()
    private val controllerId = "11111111-2222-3333-4444-555555555555".toByteArray()
    private val credentials = CompanionCredentials(
        accessoryPublicKey = HapCrypto.ed25519PublicKey(accessorySeed),
        controllerPrivateKey = controllerSeed,
        accessoryId = accessoryId,
        controllerId = controllerId,
    )

    /** The accessory side of M2 and M4, per the HAP specification. */
    private class Accessory(val seed: ByteArray, val id: ByteArray, val controllerKey: ByteArray, val controllerId: ByteArray) {
        private val ephemeralPrivate = HapCrypto.randomBytes(32)
        private val ephemeralPublic = HapCrypto.x25519PublicKey(ephemeralPrivate)
        private lateinit var shared: ByteArray
        private lateinit var sessionKey: ByteArray

        fun m2(m1: ByteArray): ByteArray {
            val controllerEphemeral = Tlv8.decode(m1)[Tlv8.Tag.PUBLIC_KEY]!!
            shared = HapCrypto.x25519SharedSecret(ephemeralPrivate, controllerEphemeral)
            sessionKey = HapCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
            val signature = HapCrypto.ed25519Sign(seed, ephemeralPublic + id + controllerEphemeral)
            val inner = Tlv8.encode(linkedMapOf(Tlv8.Tag.IDENTIFIER to id, Tlv8.Tag.SIGNATURE to signature))
            return Tlv8.encode(
                linkedMapOf(
                    Tlv8.Tag.STATE to byteArrayOf(2),
                    Tlv8.Tag.PUBLIC_KEY to ephemeralPublic,
                    Tlv8.Tag.ENCRYPTED_DATA to HapCrypto.encrypt(sessionKey, "PV-Msg02".toByteArray(), inner),
                ),
            ).also { lastControllerEphemeral = controllerEphemeral }
        }

        private lateinit var lastControllerEphemeral: ByteArray

        fun m4(m3: ByteArray): ByteArray {
            val tlv = Tlv8.decode(m3)
            val inner = Tlv8.decode(HapCrypto.decrypt(sessionKey, "PV-Msg03".toByteArray(), tlv[Tlv8.Tag.ENCRYPTED_DATA]!!))
            check(inner[Tlv8.Tag.IDENTIFIER]!!.contentEquals(controllerId))
            check(HapCrypto.ed25519Verify(controllerKey, lastControllerEphemeral + controllerId + ephemeralPublic, inner[Tlv8.Tag.SIGNATURE]!!))
            return Tlv8.encode(linkedMapOf(Tlv8.Tag.STATE to byteArrayOf(4)))
        }

        fun keys() = SessionKeys(
            output = HapCrypto.hkdf("", "ServerEncrypt-main", shared),
            input = HapCrypto.hkdf("", "ClientEncrypt-main", shared),
        )
    }

    @Test
    fun derivesMatchingSessionKeys() {
        val accessory = Accessory(accessorySeed, accessoryId, HapCrypto.ed25519PublicKey(controllerSeed), controllerId)
        val verify = PairVerify(credentials)
        val m2 = accessory.m2(verify.m1())
        val m4 = accessory.m4(verify.m3(m2))
        verify.finish(m4)
        assertArrayEquals(accessory.keys().output, verify.sessionKeys.input)
        assertArrayEquals(accessory.keys().input, verify.sessionKeys.output)
    }

    @Test
    fun rejectsAnotherAccessory() {
        val impostor = Accessory(ByteArray(32) { 7 }, accessoryId, HapCrypto.ed25519PublicKey(controllerSeed), controllerId)
        val verify = PairVerify(credentials)
        val m2 = impostor.m2(verify.m1())
        assertThrows(PairingException::class.java) { verify.m3(m2) }
    }

    @Test
    fun credentialsRoundTripAsText() {
        assertEquals(credentials, CompanionCredentials.parse(credentials.toString()))
        assertEquals(null, CompanionCredentials.parse("abc"))
    }

    /** Session framing: a counter nonce is little endian in 12 bytes; ASCII nonces are left-padded. */
    @Test
    fun noncesMatchReference() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), HapCrypto.counterNonce(1))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0) + "PS-Msg05".toByteArray(), HapCrypto.padNonce("PS-Msg05".toByteArray()))
    }
}
