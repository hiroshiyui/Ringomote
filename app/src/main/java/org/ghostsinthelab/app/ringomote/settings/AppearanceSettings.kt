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
 * How the remote is laid out: [Compact] keeps the controls at their natural size, grouped in
 * the middle like the physical remote; [Spread] stretches the touch surface and the button
 * grid to the screen edges, for big screens or thumbs that prefer large targets.
 */
enum class RemoteLayout { Compact, Spread }

/**
 * The user's appearance choices.
 *
 * [dynamicColor] asks for the wallpaper-based Material You palette; it only takes effect on
 * Android 12+, where the platform provides one, and is ignored elsewhere. [layout] picks the
 * remote's arrangement, see [RemoteLayout].
 */
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val layout: RemoteLayout = RemoteLayout.Compact,
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
    fun setLayout(layout: RemoteLayout)
}

/**
 * [AppearanceSettings] in [android.content.SharedPreferences]: three small values and nothing
 * private, so they may be included in backups. An unknown stored mode falls back to the
 * default rather than crashing, so renaming a [ThemeMode] or [RemoteLayout] later is safe.
 */
class SharedPreferencesAppearanceSettings(context: Context) : AppearanceSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _appearance = MutableStateFlow(
        Appearance(
            mode = prefs.getString(KEY_MODE, null)?.let { stored -> ThemeMode.entries.find { it.name == stored } }
                ?: ThemeMode.System,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            layout = prefs.getString(KEY_LAYOUT, null)?.let { stored -> RemoteLayout.entries.find { it.name == stored } }
                ?: RemoteLayout.Compact,
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

    override fun setLayout(layout: RemoteLayout) {
        _appearance.update { it.copy(layout = layout) }
        prefs.edit { putString(KEY_LAYOUT, layout.name) }
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_LAYOUT = "remote_layout"
    }
}
