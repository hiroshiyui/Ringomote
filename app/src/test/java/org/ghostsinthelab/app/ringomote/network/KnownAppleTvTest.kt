package org.ghostsinthelab.app.ringomote.network

import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging Apple TVs from Companion pairings, Bonjour and Bluetooth bonds by their shared name. */
class KnownAppleTvTest {
    private val livingRoom = AppleTv("客廳", "客廳", "AppleTV6,2", "192.168.50.48", 49153)
    private val bedroom = AppleTv("Bedroom", "Bedroom", "AppleTV14,1", "192.168.50.60", 49153)

    /** One TV seen everywhere merges into one entry with every flag set. */
    @Test
    fun mergesByName() {
        val host = HostDevice("AA:BB:CC:DD:EE:FF", "客廳")
        val merged = mergeKnownAppleTvs(setOf("客廳"), listOf(livingRoom), listOf(host), host)
        assertEquals(1, merged.size)
        val tv = merged.single()
        assertEquals("Apple TV 4K", tv.modelName)
        assertTrue(tv.pairedForWaking && tv.onNetwork && tv.bonded && tv.connected)
    }

    /** Pairings and discovered TVs both count even when the other source lacks them. */
    @Test
    fun unionOfSources() {
        val merged = mergeKnownAppleTvs(setOf("Bedroom"), listOf(livingRoom), emptyList(), null)
        assertEquals(listOf("Bedroom", "客廳"), merged.map { it.name })
        assertFalse(merged[0].onNetwork)
        assertTrue(merged[0].pairedForWaking)
        assertTrue(merged[1].onNetwork)
        assertFalse(merged[1].pairedForWaking)
    }

    /** Bonded devices that are not Apple TVs (headphones) stay out unless matched by name. */
    @Test
    fun ignoresUnrelatedBonds() {
        val headphones = HostDevice("11:22:33:44:55:66", "WH-1000XM4")
        val merged = mergeKnownAppleTvs(emptySet(), listOf(bedroom), listOf(headphones, HostDevice("x", "Bedroom")), null)
        assertEquals(listOf("Bedroom"), merged.map { it.name })
        assertTrue(merged.single().bonded)
    }
}
