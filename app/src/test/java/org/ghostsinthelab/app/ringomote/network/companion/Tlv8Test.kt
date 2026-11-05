package org.ghostsinthelab.app.ringomote.network.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Tlv8Test {
    /** Items keep their order; a value over 255 bytes splits and merges back. */
    @Test
    fun roundTripsLongValues() {
        val long = ByteArray(300) { it.toByte() }
        val encoded = Tlv8.encode(linkedMapOf(Tlv8.Tag.STATE to byteArrayOf(3), Tlv8.Tag.PUBLIC_KEY to long))
        assertEquals(3 + (2 + 255) + (2 + 45), encoded.size)
        assertEquals(Tlv8.Tag.STATE, encoded[0].toInt())
        val decoded = Tlv8.decode(encoded)
        assertArrayEquals(byteArrayOf(3), decoded[Tlv8.Tag.STATE])
        assertArrayEquals(long, decoded[Tlv8.Tag.PUBLIC_KEY])
    }

    /** The pyatv doc example: state M1 and a 32-byte key. */
    @Test
    fun encodesStateAndKey() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val encoded = Tlv8.encode(linkedMapOf(Tlv8.Tag.STATE to byteArrayOf(1), Tlv8.Tag.PUBLIC_KEY to key))
        assertArrayEquals(byteArrayOf(0x06, 0x01, 0x01, 0x03, 0x20) + key, encoded)
    }
}
