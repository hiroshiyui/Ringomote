package org.ghostsinthelab.app.ringomote.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers whether Ringomote has ever been connected to a host.
 *
 * Android cannot reliably tell an Apple TV apart from the phone's other paired devices
 * (headphones, keyboards, cars), so "has connected before" stands in for "has an Apple TV paired
 * with this app". The UI uses it to open the pairing details automatically on first use.
 */
interface ConnectionHistory {
    /** True once a host has been connected at least once; never goes back to false. */
    val hasConnectedBefore: StateFlow<Boolean>

    /** Records a successful connection. Idempotent. */
    fun markConnected()
}

/**
 * [ConnectionHistory] stored in [android.content.SharedPreferences]: one boolean, no device
 * address or name, so it carries nothing private and may be included in backups.
 */
class SharedPreferencesConnectionHistory(context: Context) : ConnectionHistory {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _hasConnectedBefore = MutableStateFlow(prefs.getBoolean(KEY_CONNECTED_BEFORE, false))
    override val hasConnectedBefore: StateFlow<Boolean> = _hasConnectedBefore.asStateFlow()

    override fun markConnected() {
        if (_hasConnectedBefore.value) return
        _hasConnectedBefore.value = true
        prefs.edit().putBoolean(KEY_CONNECTED_BEFORE, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "connection_history"
        const val KEY_CONNECTED_BEFORE = "connected_before"
    }
}
