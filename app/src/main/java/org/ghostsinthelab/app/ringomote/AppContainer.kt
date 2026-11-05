package org.ghostsinthelab.app.ringomote

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ghostsinthelab.app.ringomote.settings.AppearanceSettings
import org.ghostsinthelab.app.ringomote.settings.SharedPreferencesAppearanceSettings
import org.ghostsinthelab.app.ringomote.network.AppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.LocalNetwork
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.NsdAppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.SharedPreferencesNetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.CompanionSession
import org.ghostsinthelab.app.ringomote.ui.settings.AppleTvViewModel
import org.ghostsinthelab.app.ringomote.network.companion.SharedPreferencesCompanionCredentialsStore

/**
 * Manual dependency wiring: one place that decides which implementations the app uses.
 *
 * Objects live as long as the process, because the remote session's held-key state must
 * outlive any single screen. Swap implementations here without touching the UI.
 */
class AppContainer(context: Context) {
    val appearanceSettings: AppearanceSettings by lazy { SharedPreferencesAppearanceSettings(context) }
    val networkSettings: NetworkSettings by lazy { SharedPreferencesNetworkSettings(context) }
    val appleTvDiscovery: AppleTvDiscovery by lazy { NsdAppleTvDiscovery(context) }
    val localNetwork: LocalNetwork by lazy { LocalNetwork(context) }
    val companionCredentials: CompanionCredentialsStore by lazy { SharedPreferencesCompanionCredentialsStore(context) }

    /** The remote session over Wi-Fi; process lifetime, held only while the remote wants it. */
    val companionSession: CompanionSession by lazy {
        CompanionSession(
            CompanionSession.ClientPort(companionCredentials, appleTvDiscovery, localNetwork::bind, AppleTvViewModel.DISPLAY_NAME, Build.MODEL),
            applicationScope,
            Dispatchers.IO,
        )
    }

    /**
     * The one serial dispatcher for everything that sends input, so presses reach the Apple TV
     * in the order the user produced them, and a release queued by a closing screen runs
     * before anything queued by the next one.
     */
    val serialDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Process-lifetime scope for work that must finish after a screen is gone. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + serialDispatcher)
}

/** Application entry point; owns the [AppContainer]. */
class RingomoteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
