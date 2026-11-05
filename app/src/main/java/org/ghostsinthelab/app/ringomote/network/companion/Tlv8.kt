package org.ghostsinthelab.app.ringomote.network.companion

import java.io.ByteArrayOutputStream

/**
 * HomeKit Accessory Protocol TLV8: a sequence of `tag (1 byte) | length (1 byte) | value`.
 *
 * A value longer than 255 bytes is written as consecutive items with the same tag, and
 * consecutive items with the same tag are joined again on reading. An empty value writes
 * nothing, matching the reference implementation the Apple TV has been seen to accept.
 */
object Tlv8 {
    /** Tags used by pair-setup and pair-verify. */
    object Tag {
        const val METHOD = 0x00
        const val IDENTIFIER = 0x01
        const val SALT = 0x02
        const val PUBLIC_KEY = 0x03
        const val PROOF = 0x04
        const val ENCRYPTED_DATA = 0x05
        const val STATE = 0x06
        const val ERROR = 0x07
        const val SIGNATURE = 0x0A
        const val NAME = 0x11
        const val FLAGS = 0x13
    }

    /** Encodes [items] in iteration order; use a [LinkedHashMap] when the order matters. */
    fun encode(items: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            while (pos < value.size) {
                val size = minOf(value.size - pos, 255)
                out.write(tag)
                out.write(size)
                out.write(value, pos, size)
                pos += size
            }
        }
        return out.toByteArray()
    }

    /** Decodes [data]; throws [IllegalArgumentException] on a truncated item. */
    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos < data.size) {
            require(pos + 2 <= data.size) { "Truncated TLV header at $pos" }
            val tag = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            require(pos + 2 + length <= data.size) { "Truncated TLV value for tag $tag" }
            val value = data.copyOfRange(pos + 2, pos + 2 + length)
            result[tag] = result[tag]?.plus(value) ?: value
            pos += 2 + length
        }
        return result
    }
}
