package org.ghostsinthelab.app.ringomote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging Apple TVs from Companion pairings and Bonjour by their shared name. */
class KnownAppleTvTest {
    private val livingRoom = AppleTv("客廳", "客廳", "AppleTV6,2", "192.168.50.48", 49153)

    /** One TV seen in both sources merges into one entry with both flags set. */
    @Test
    fun mergesByName() {
        val merged = mergeKnownAppleTvs(setOf("客廳"), listOf(livingRoom))
        assertEquals(1, merged.size)
        val tv = merged.single()
        assertEquals("Apple TV 4K", tv.modelName)
        assertTrue(tv.paired && tv.onNetwork)
    }

    /** Pairings and discovered TVs both count even when the other source lacks them. */
    @Test
    fun unionOfSources() {
        val merged = mergeKnownAppleTvs(setOf("Bedroom"), listOf(livingRoom))
        assertEquals(listOf("Bedroom", "客廳"), merged.map { it.name })
        assertFalse(merged[0].onNetwork)
        assertTrue(merged[0].paired)
        assertTrue(merged[1].onNetwork)
        assertFalse(merged[1].paired)
    }
}
