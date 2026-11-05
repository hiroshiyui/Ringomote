package org.ghostsinthelab.app.ringomote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Apple TV filter on the Companion Link TXT record, and the model names shown in Settings. */
class CompanionLinkTest {

    /** Only `rpMd` values starting with AppleTV count; phones, Macs and HomePods share the service. */
    @Test
    fun appleTvFilterUsesModelPrefix() {
        assertTrue(CompanionLink.isAppleTv("AppleTV14,1"))
        assertTrue(CompanionLink.isAppleTv("AppleTV5,3"))
        assertFalse(CompanionLink.isAppleTv("iPhone14,2"))
        assertFalse(CompanionLink.isAppleTv("MacBookPro18,1"))
        assertFalse(CompanionLink.isAppleTv("AudioAccessory5,1"))
        assertFalse(CompanionLink.isAppleTv(null))
        assertFalse(CompanionLink.isAppleTv(""))
    }

    /** Known identifiers map to marketing names; unknown ones are shown as they are. */
    @Test
    fun modelNamesFallBackToIdentifier() {
        assertEquals("Apple TV 4K (3rd generation)", AppleTv("a", "keting", "AppleTV14,1", "h", 1).modelName)
        assertEquals("Apple TV HD", AppleTvModels.nameOf("AppleTV5,3"))
        assertEquals("AppleTV99,9", AppleTvModels.nameOf("AppleTV99,9"))
    }
}
