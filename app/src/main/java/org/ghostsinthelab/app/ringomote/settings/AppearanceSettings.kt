package org.ghostsinthelab.app.ringomote.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Which colour scheme the app uses: the system's choice, or always light or dark. */
enum class ThemeMode { System, Light, Dark }

/**
 * The user's appearance choices.
 *
 * [dynamicColor] asks for the wallpaper-based Material You palette; it only takes effect on
 * Android 12+, where the platform provides one, and is ignored elsewhere.
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
) {
    /** Whether the dark scheme applies, given whether the system is currently in dark mode. */
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** Stores the user's [Appearance] and publishes changes to the UI. */
interface AppearanceSettings {
    val appearance: StateFlow<Appearance>
    fun setMode(mode: ThemeMode)
    fun setDynamicColor(enabled: Boolean)
}

/**
 * [AppearanceSettings] in [android.content.SharedPreferences]: two small values and nothing
 * private, so they may be included in backups. An unknown stored mode falls back to the
 * default rather than crashing, so renaming a [ThemeMode] later is safe.
 */
class SharedPreferencesAppearanceSettings(context: Context) : AppearanceSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _appearance = MutableStateFlow(
        Appearance(
            mode = prefs.getString(KEY_MODE, null)?.let { stored -> ThemeMode.entries.find { it.name == stored } }
                ?: ThemeMode.System,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
        ),
    )
    override val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    override fun setMode(mode: ThemeMode) {
        _appearance.update { it.copy(mode = mode) }
        prefs.edit { putString(KEY_MODE, mode.name) }
    }

    override fun setDynamicColor(enabled: Boolean) {
        _appearance.update { it.copy(dynamicColor = enabled) }
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
