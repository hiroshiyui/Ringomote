package org.ghostsinthelab.app.ringomote.network

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's network choices: which Apple TV the remote is aimed at, and this phone's name. */
interface NetworkSettings {
    /** The Apple TV the Remote tab is aimed at, by name; null until the user picks one. */
    val selectedAppleTv: StateFlow<String?>
    fun setSelectedAppleTv(name: String?)

    /**
     * The name this phone pairs under on the Apple TV, without the app-name prefix; null (or
     * blank) means the phone's own name. Two phones of the same model in one home need this.
     */
    val remoteName: StateFlow<String?>
    fun setRemoteName(name: String?)
}

/** [NetworkSettings] in [android.content.SharedPreferences]: two names, nothing private. */
class SharedPreferencesNetworkSettings(context: Context) : NetworkSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    override val selectedAppleTv: StateFlow<String?> = _selected.asStateFlow()

    override fun setSelectedAppleTv(name: String?) {
        _selected.value = name
        prefs.edit { putString(KEY_SELECTED, name) }
    }

    private val _remoteName = MutableStateFlow(prefs.getString(KEY_REMOTE_NAME, null))
    override val remoteName: StateFlow<String?> = _remoteName.asStateFlow()

    override fun setRemoteName(name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        _remoteName.value = trimmed
        prefs.edit { putString(KEY_REMOTE_NAME, trimmed) }
    }

    private companion object {
        const val PREFS_NAME = "network"
        const val KEY_REMOTE_NAME = "remote_name"
        const val KEY_SELECTED = "selected_apple_tv"
    }
}
