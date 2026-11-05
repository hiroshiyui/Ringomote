package org.ghostsinthelab.app.ringomote.network.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The OPACK codec against the examples in pyatv's protocol notes (corrected for little-endian
 * and shortest-form encoding, which is what real devices send) and a Companion request round trip.
 */
class OpackTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Examples from pyatv's protocol documentation. */
    @Test
    fun decodesDocumentedExamples() {
        assertEquals(mapOf("a" to "b"), Opack.decode(hex("EF4161416203")))
        assertEquals(mapOf("foo" to 15L), Opack.decode(hex("E16103666F6F17")))
        assertEquals(listOf(true, "foo"), Opack.decode(hex("D2016103666F6F")))
        assertEquals(32L, Opack.decode(hex("3020")))
        assertEquals(32L, Opack.decode(hex("312000")))
        assertEquals(listOf("foo", "bar", "foo", "bar"), Opack.decode(hex("D443666F6F43626172A0A1")))
        assertEquals(mapOf("a" to false, "b" to "test", "c" to "test"), Opack.decode(hex("E3416102416244746573744163A2")))
        assertArrayEquals(hex("AABB"), Opack.decode(hex("72AABB")) as ByteArray)
    }

    @Test
    fun encodesSmallValuesCompactly() {
        assertArrayEquals(hex("E143666F6F17"), Opack.encode(mapOf("foo" to 15)))
        assertArrayEquals(hex("28"), Opack.encode(32))
        assertArrayEquals(hex("30C8"), Opack.encode(200))
        assertArrayEquals(hex("310001"), Opack.encode(256))
        assertArrayEquals(hex("D20143666F6F"), Opack.encode(listOf(true, "foo")))
        assertArrayEquals(hex("D443666F6F43626172A0A1"), Opack.encode(listOf("foo", "bar", "foo", "bar")))
    }

    /** A Companion request survives a round trip, including bytes and nested maps. */
    @Test
    fun roundTripsRequest() {
        val message = mapOf(
            "_i" to "_hidC",
            "_t" to 2L,
            "_x" to 70000L,
            "_c" to mapOf("_hBtS" to 1L, "_hidC" to 13L, "_pd" to ByteArray(40) { it.toByte() }),
        )
        val decoded = Opack.decode(Opack.encode(message)) as Map<*, *>
        assertEquals("_hidC", decoded["_i"])
        assertEquals(70000L, decoded["_x"])
        val content = decoded["_c"] as Map<*, *>
        assertEquals(13L, content["_hidC"])
        assertArrayEquals(ByteArray(40) { it.toByte() }, content["_pd"] as ByteArray)
    }
}
