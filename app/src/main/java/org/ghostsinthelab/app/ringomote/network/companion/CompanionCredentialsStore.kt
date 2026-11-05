package org.ghostsinthelab.app.ringomote.network.companion

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairings with Apple TVs, keyed by the TV's Bonjour service name, plus this phone's
 * identity on the Companion link (the display name it registered and a device id).
 *
 * The credentials contain this phone's long-term private key, so the file is excluded from
 * backups (`backup_rules.xml`, `data_extraction_rules.xml`); a restored phone pairs again.
 */
interface CompanionCredentialsStore {
    /** Service name → credentials. */
    val credentials: StateFlow<Map<String, CompanionCredentials>>
    fun get(appleTvId: String): CompanionCredentials? = credentials.value[appleTvId]
    fun put(appleTvId: String, credentials: CompanionCredentials)
    fun remove(appleTvId: String)

    /** This phone's device id on the link, generated once. */
    val deviceId: String
}

class SharedPreferencesCompanionCredentialsStore(context: Context) : CompanionCredentialsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _credentials = MutableStateFlow(load())
    override val credentials: StateFlow<Map<String, CompanionCredentials>> = _credentials.asStateFlow()

    override val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: CompanionClient.newDeviceId().also { id ->
            prefs.edit { putString(KEY_DEVICE_ID, id) }
        }

    override fun put(appleTvId: String, credentials: CompanionCredentials) {
        prefs.edit { putString(PREFIX + appleTvId, credentials.toString()) }
        _credentials.value = load()
    }

    override fun remove(appleTvId: String) {
        prefs.edit { remove(PREFIX + appleTvId) }
        _credentials.value = load()
    }

    private fun load(): Map<String, CompanionCredentials> =
        prefs.all.entries
            .filter { it.key.startsWith(PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let(CompanionCredentials::parse)?.let { key.removePrefix(PREFIX) to it } }
            .toMap()

    companion object {
        const val PREFS_NAME = "companion"
        private const val PREFIX = "credentials."
        private const val KEY_DEVICE_ID = "device_id"
    }
}
