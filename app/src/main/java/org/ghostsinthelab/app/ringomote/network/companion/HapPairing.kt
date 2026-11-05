package org.ghostsinthelab.app.ringomote.network.companion

import java.util.UUID

/** Thrown when the Apple TV rejects a pairing step or its reply does not verify. */
class PairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * HAP pair-setup, client side, as a transport-free state machine over TLV8 byte arrays.
 *
 * Call [m1] and send its TLV in a `PS_Start` frame; feed the reply's TLV to [m3] and send
 * that in `PS_Next`; feed the reply to [m5] and send; feed the final reply to [finish] to
 * obtain [CompanionCredentials]. Between [m1] and [m3] the Apple TV shows the PIN.
 *
 * Keys: a fresh Ed25519 seed is this phone's long-term key, and doubles as the SRP private
 * exponent, as the reference client does. [displayName] is shown on the Apple TV under
 * Remotes and Devices, carried in the Companion-specific OPACK item of M5.
 */
class PairSetup(
    private val displayName: String,
    private val seed: ByteArray = HapCrypto.randomBytes(32),
    private val controllerId: ByteArray = UUID.randomUUID().toString().toByteArray(),
) {
    private val publicKey = HapCrypto.ed25519PublicKey(seed)
    private var srp: HapSrpClient? = null
    private var salt: ByteArray? = null
    private var serverPublic: ByteArray? = null
    private var sessionKey: ByteArray? = null

    fun m1(): ByteArray = Tlv8.encode(
        linkedMapOf(Tlv8.Tag.METHOD to byteArrayOf(0), Tlv8.Tag.STATE to byteArrayOf(1)),
    )

    /** Takes M2 (salt and the accessory's SRP public value) and the PIN; returns M3. */
    fun m3(m2: ByteArray, pin: String): ByteArray {
        val tlv = decodeChecked(m2, expectedState = 2)
        val salt = tlv[Tlv8.Tag.SALT] ?: throw PairingException("M2 without salt")
        val serverPublic = tlv[Tlv8.Tag.PUBLIC_KEY] ?: throw PairingException("M2 without public key")
        val srp = HapSrpClient(pin, seed)
        val proof = srp.process(salt, serverPublic)
        this.srp = srp
        this.salt = salt
        this.serverPublic = serverPublic
        return Tlv8.encode(
            linkedMapOf(
                Tlv8.Tag.STATE to byteArrayOf(3),
                Tlv8.Tag.PUBLIC_KEY to srp.publicKey,
                Tlv8.Tag.PROOF to proof,
            ),
        )
    }

    /** Takes M4 (the accessory's proof); returns M5 with this phone's signed identity. */
    fun m5(m4: ByteArray): ByteArray {
        val srp = srp ?: throw IllegalStateException("m3() not called")
        val tlv = decodeChecked(m4, expectedState = 4)
        val proof = tlv[Tlv8.Tag.PROOF] ?: throw PairingException("M4 without proof")
        if (!srp.verifyServerProof(proof)) throw PairingException("Wrong PIN or the Apple TV's proof did not verify")

        val signingKey = HapCrypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", srp.key)
        val sessionKey = HapCrypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", srp.key)
        this.sessionKey = sessionKey
        val signature = HapCrypto.ed25519Sign(seed, signingKey + controllerId + publicKey)
        val inner = Tlv8.encode(
            linkedMapOf(
                Tlv8.Tag.IDENTIFIER to controllerId,
                Tlv8.Tag.PUBLIC_KEY to publicKey,
                Tlv8.Tag.SIGNATURE to signature,
                Tlv8.Tag.NAME to Opack.encode(mapOf("name" to displayName)),
            ),
        )
        val encrypted = HapCrypto.encrypt(sessionKey, "PS-Msg05".toByteArray(), inner)
        return Tlv8.encode(
            linkedMapOf(Tlv8.Tag.STATE to byteArrayOf(5), Tlv8.Tag.ENCRYPTED_DATA to encrypted),
        )
    }

    /** Takes M6 and returns the credentials, after checking the accessory's signature. */
    fun finish(m6: ByteArray): CompanionCredentials {
        val sessionKey = sessionKey ?: throw IllegalStateException("m5() not called")
        val srp = srp!!
        val tlv = decodeChecked(m6, expectedState = 6)
        val encrypted = tlv[Tlv8.Tag.ENCRYPTED_DATA] ?: throw PairingException("M6 without encrypted data")
        val inner = try {
            Tlv8.decode(HapCrypto.decrypt(sessionKey, "PS-Msg06".toByteArray(), encrypted))
        } catch (e: Exception) {
            throw PairingException("M6 did not decrypt", e)
        }
        val accessoryId = inner[Tlv8.Tag.IDENTIFIER] ?: throw PairingException("M6 without identifier")
        val accessoryKey = inner[Tlv8.Tag.PUBLIC_KEY] ?: throw PairingException("M6 without public key")
        val signature = inner[Tlv8.Tag.SIGNATURE] ?: throw PairingException("M6 without signature")
        val accessorySigningKey = HapCrypto.hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", srp.key)
        if (!HapCrypto.ed25519Verify(accessoryKey, accessorySigningKey + accessoryId + accessoryKey, signature)) {
            throw PairingException("The Apple TV's signature did not verify")
        }
        return CompanionCredentials(accessoryKey, seed, accessoryId, controllerId)
    }

    private fun decodeChecked(data: ByteArray, expectedState: Int): Map<Int, ByteArray> {
        val tlv = try { Tlv8.decode(data) } catch (e: IllegalArgumentException) { throw PairingException("Malformed pairing data", e) }
        tlv[Tlv8.Tag.ERROR]?.let { throw PairingException(errorText(it)) }
        val state = tlv[Tlv8.Tag.STATE]?.firstOrNull()?.toInt()
        if (state != expectedState) throw PairingException("Expected pairing state M$expectedState, got $state")
        return tlv
    }

    private fun errorText(code: ByteArray): String = when (code.firstOrNull()?.toInt()) {
        2 -> "The Apple TV rejected the PIN"
        3 -> "The Apple TV asks to wait before trying again"
        4 -> "The Apple TV has too many paired devices"
        5 -> "Too many failed attempts; the Apple TV refuses to pair"
        6 -> "The Apple TV is not available for pairing"
        7 -> "The Apple TV is busy pairing with another device"
        else -> "Pairing error ${code.firstOrNull()}"
    }
}

/**
 * HAP pair-verify, client side: proves both sides hold the keys from pair-setup and derives
 * the session keys. [m1] goes in `PV_Start`; the reply goes to [m3], sent in `PV_Next`; the
 * final reply goes to [finish]. Afterwards [sessionKeys] gives the Companion session keys.
 */
class PairVerify(
    private val credentials: CompanionCredentials,
    private val ephemeralPrivate: ByteArray = HapCrypto.randomBytes(32),
) {
    private val ephemeralPublic = HapCrypto.x25519PublicKey(ephemeralPrivate)
    private var shared: ByteArray? = null

    fun m1(): ByteArray = Tlv8.encode(
        linkedMapOf(Tlv8.Tag.STATE to byteArrayOf(1), Tlv8.Tag.PUBLIC_KEY to ephemeralPublic),
    )

    fun m3(m2: ByteArray): ByteArray {
        val tlv = decodeChecked(m2, expectedState = 2)
        val accessoryEphemeral = tlv[Tlv8.Tag.PUBLIC_KEY] ?: throw PairingException("M2 without public key")
        val encrypted = tlv[Tlv8.Tag.ENCRYPTED_DATA] ?: throw PairingException("M2 without encrypted data")
        val shared = HapCrypto.x25519SharedSecret(ephemeralPrivate, accessoryEphemeral)
        val sessionKey = HapCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val inner = try {
            Tlv8.decode(HapCrypto.decrypt(sessionKey, "PV-Msg02".toByteArray(), encrypted))
        } catch (e: Exception) {
            throw PairingException("M2 did not decrypt", e)
        }
        val id = inner[Tlv8.Tag.IDENTIFIER] ?: throw PairingException("M2 without identifier")
        val signature = inner[Tlv8.Tag.SIGNATURE] ?: throw PairingException("M2 without signature")
        if (!id.contentEquals(credentials.accessoryId)) throw PairingException("Not the Apple TV this phone paired with")
        val signed = accessoryEphemeral + id + ephemeralPublic
        if (!HapCrypto.ed25519Verify(credentials.accessoryPublicKey, signed, signature)) {
            throw PairingException("The Apple TV's signature did not verify")
        }
        this.shared = shared
        val ourSignature = HapCrypto.ed25519Sign(
            credentials.controllerPrivateKey,
            ephemeralPublic + credentials.controllerId + accessoryEphemeral,
        )
        val reply = Tlv8.encode(
            linkedMapOf(Tlv8.Tag.IDENTIFIER to credentials.controllerId, Tlv8.Tag.SIGNATURE to ourSignature),
        )
        return Tlv8.encode(
            linkedMapOf(
                Tlv8.Tag.STATE to byteArrayOf(3),
                Tlv8.Tag.ENCRYPTED_DATA to HapCrypto.encrypt(sessionKey, "PV-Msg03".toByteArray(), reply),
            ),
        )
    }

    fun finish(m4: ByteArray) {
        decodeChecked(m4, expectedState = 4)
    }

    /** Output (phone → Apple TV) and input keys for the encrypted Companion session. */
    val sessionKeys: SessionKeys
        get() {
            val shared = shared ?: throw IllegalStateException("m3() not called")
            return SessionKeys(
                output = HapCrypto.hkdf("", "ClientEncrypt-main", shared),
                input = HapCrypto.hkdf("", "ServerEncrypt-main", shared),
            )
        }

    private fun decodeChecked(data: ByteArray, expectedState: Int): Map<Int, ByteArray> {
        val tlv = try { Tlv8.decode(data) } catch (e: IllegalArgumentException) { throw PairingException("Malformed pairing data", e) }
        tlv[Tlv8.Tag.ERROR]?.let {
            throw PairingException(
                if (it.firstOrNull()?.toInt() == 2) "The Apple TV no longer knows this pairing" else "Pairing error ${it.firstOrNull()}",
            )
        }
        val state = tlv[Tlv8.Tag.STATE]?.firstOrNull()?.toInt()
        if (state != expectedState) throw PairingException("Expected verify state M$expectedState, got $state")
        return tlv
    }
}

data class SessionKeys(val output: ByteArray, val input: ByteArray)
