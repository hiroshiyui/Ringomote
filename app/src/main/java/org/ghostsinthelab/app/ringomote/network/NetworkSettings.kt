package org.ghostsinthelab.app.ringomote.network

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's network choices: which Apple TV the remote is aimed at. */
interface NetworkSettings {
    /** The Apple TV the Remote tab is aimed at, by name; null until the user picks one. */
    val selectedAppleTv: StateFlow<String?>
    fun setSelectedAppleTv(name: String?)
}

/** [NetworkSettings] in [android.content.SharedPreferences]: one name, nothing private. */
class SharedPreferencesNetworkSettings(context: Context) : NetworkSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    override val selectedAppleTv: StateFlow<String?> = _selected.asStateFlow()

    override fun setSelectedAppleTv(name: String?) {
        _selected.value = name
        prefs.edit { putString(KEY_SELECTED, name) }
    }

    private companion object {
        const val PREFS_NAME = "network"
        const val KEY_SELECTED = "selected_apple_tv"
    }
}
