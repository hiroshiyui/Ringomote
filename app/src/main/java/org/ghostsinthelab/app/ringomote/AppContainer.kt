package org.ghostsinthelab.app.ringomote

import android.app.Application
import android.content.Context
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothHidDeviceTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
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
