package org.ghostsinthelab.app.ringomote

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothHidDeviceTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.history.ConnectionHistory
import org.ghostsinthelab.app.ringomote.history.SharedPreferencesConnectionHistory
import org.ghostsinthelab.app.ringomote.remote.RemoteSession

/**
 * Manual dependency wiring: one place that decides which implementations the app uses.
 *
 * Objects live as long as the process, because only one HID device registration exists per
 * phone and the session's held-key state must outlive any single screen. Swap implementations
 * here (e.g. a BLE transport) without touching the UI.
 */
class AppContainer(context: Context) {
    val transport: HidTransport by lazy { BluetoothHidDeviceTransport(context) }
    val session: RemoteSession by lazy { RemoteSession(transport) }
    val connectionHistory: ConnectionHistory by lazy { SharedPreferencesConnectionHistory(context) }

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
