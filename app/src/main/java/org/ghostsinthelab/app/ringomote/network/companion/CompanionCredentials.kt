package org.ghostsinthelab.app.ringomote.network.companion

/**
 * What a completed pair-setup leaves behind, enough to pair-verify forever after.
 *
 * [accessoryPublicKey] is the Apple TV's long-term Ed25519 public key and [accessoryId] its
 * pairing identifier; [controllerPrivateKey] is this phone's Ed25519 seed and [controllerId]
 * the UUID it registered under. The string form is four lowercase hex fields joined by
 * colons in that order, the same layout the pyatv tool prints, so a pairing made with either
 * can be carried over.
 */
data class CompanionCredentials(
    val accessoryPublicKey: ByteArray,
    val controllerPrivateKey: ByteArray,
    val accessoryId: ByteArray,
    val controllerId: ByteArray,
) {
    override fun toString(): String =
        listOf(accessoryPublicKey, controllerPrivateKey, accessoryId, controllerId).joinToString(":") { it.toHex() }

    override fun equals(other: Any?): Boolean = other is CompanionCredentials && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    companion object {
        /** Parses the colon-separated form, or returns null if it is not one. */
        fun parse(text: String): CompanionCredentials? {
            val parts = text.trim().split(":")
            if (parts.size != 4) return null
            val fields = parts.map { it.hexToBytesOrNull() ?: return null }
            if (fields.any { it.isEmpty() }) return null
            return CompanionCredentials(fields[0], fields[1], fields[2], fields[3])
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0 || any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
