package org.ghostsinthelab.app.ringomote

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ghostsinthelab.app.ringomote.bluetooth.BleHidTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.diagnostics.DiagnosticsReport
import org.ghostsinthelab.app.ringomote.diagnostics.EventLog
import org.ghostsinthelab.app.ringomote.history.ConnectionHistory
import org.ghostsinthelab.app.ringomote.history.SharedPreferencesConnectionHistory
import org.ghostsinthelab.app.ringomote.remote.RemoteSession
import org.ghostsinthelab.app.ringomote.settings.AppearanceSettings
import org.ghostsinthelab.app.ringomote.settings.SharedPreferencesAppearanceSettings
import org.ghostsinthelab.app.ringomote.network.AppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.LocalNetwork
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.NsdAppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.SharedPreferencesNetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.SharedPreferencesCompanionCredentialsStore

/**
 * Manual dependency wiring: one place that decides which implementations the app uses.
 *
 * Objects live as long as the process, because only one HID device registration exists per
 * phone and the session's held-key state must outlive any single screen. Swap implementations
 * here (the classic-HID [org.ghostsinthelab.app.ringomote.bluetooth.BluetoothHidDeviceTransport] is
 * kept as an alternative) without touching the UI.
 */
class AppContainer(context: Context) {
    val eventLog: EventLog by lazy { EventLog(mirror = { Log.i("Ringomote", it) }) }
    val transport: HidTransport by lazy { BleHidTransport(context, eventLog) }
    val diagnosticsReport: DiagnosticsReport by lazy { DiagnosticsReport(context, transport, eventLog) }
    val session: RemoteSession by lazy { RemoteSession(transport) }
    val connectionHistory: ConnectionHistory by lazy { SharedPreferencesConnectionHistory(context) }
    val appearanceSettings: AppearanceSettings by lazy { SharedPreferencesAppearanceSettings(context) }
    val networkSettings: NetworkSettings by lazy { SharedPreferencesNetworkSettings(context) }
    val appleTvDiscovery: AppleTvDiscovery by lazy { NsdAppleTvDiscovery(context) }
    val localNetwork: LocalNetwork by lazy { LocalNetwork(context) }
    val companionCredentials: CompanionCredentialsStore by lazy { SharedPreferencesCompanionCredentialsStore(context) }

    /**
     * The one serial dispatcher for everything that talks to the transport: input, connect,
     * start and stop. Sharing it app-wide keeps reports in order and guarantees that a stop
     * queued by a closing screen runs before a start queued by the next one.
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
