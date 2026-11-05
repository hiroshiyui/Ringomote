package org.ghostsinthelab.app.ringomote.network

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has allowed the app to use the local network at all.
 *
 * Off by default: the remote itself is Bluetooth only, and the network features (finding
 * Apple TVs with Bonjour, and later waking one) exist only for the user who asks for them.
 */
interface NetworkSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)

    /** The Apple TV the Remote tab is aimed at, by name; null until the user picks one. */
    val selectedAppleTv: StateFlow<String?>
    fun setSelectedAppleTv(name: String?)
}

/** [NetworkSettings] in [android.content.SharedPreferences]: one boolean, nothing private. */
class SharedPreferencesNetworkSettings(context: Context) : NetworkSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    override val selectedAppleTv: StateFlow<String?> = _selected.asStateFlow()

    override fun setSelectedAppleTv(name: String?) {
        _selected.value = name
        prefs.edit { putString(KEY_SELECTED, name) }
    }

    private companion object {
        const val PREFS_NAME = "network"
        const val KEY_ENABLED = "enabled"
        const val KEY_SELECTED = "selected_apple_tv"
    }
}
