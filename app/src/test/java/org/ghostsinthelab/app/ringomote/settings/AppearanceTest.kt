package org.ghostsinthelab.app.ringomote.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins down how [Appearance.isDark] combines the chosen [ThemeMode] with the system setting. */
class AppearanceTest {

    /** System mode follows whatever the system says. */
    @Test
    fun systemModeFollowsSystem() {
        assertTrue(Appearance(ThemeMode.System).isDark(systemDark = true))
        assertFalse(Appearance(ThemeMode.System).isDark(systemDark = false))
    }

    /** Light and Dark ignore the system setting. */
    @Test
    fun explicitModesIgnoreSystem() {
        assertFalse(Appearance(ThemeMode.Light).isDark(systemDark = true))
        assertTrue(Appearance(ThemeMode.Dark).isDark(systemDark = false))
    }

    /** Defaults: follow the system, with dynamic colour on and the compact layout. */
    @Test
    fun defaultsFollowSystemWithDynamicColor() {
        val default = Appearance()
        assertEquals(ThemeMode.System, default.mode)
        assertTrue(default.dynamicColor)
        assertEquals(RemoteLayout.Compact, default.layout)
    }
}
