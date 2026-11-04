package org.ghostsinthelab.app.ringomote.diagnostics

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothPermissions
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the plain-text Bluetooth diagnostics report shown under Settings > Advanced.
 *
 * It gathers what a developer asks for first when a user reports "it does not connect": the
 * app and Android versions, the phone model, the state of the Bluetooth adapter and of the
 * LE advertiser, which permissions are granted, the transport's current state, the devices
 * paired with the phone, and the transport's [EventLog]. Everything is read at [build] time
 * on the caller's thread; nothing is stored or sent. The report names paired devices and
 * their Bluetooth addresses, so it is shared only when the user chooses to.
 */
class DiagnosticsReport(
    context: Context,
    private val transport: HidTransport,
    private val eventLog: EventLog,
) {
    private val appContext = context.applicationContext

    /** The complete report, ready to show, copy, share or save. */
    fun build(): String = buildString {
        val now = Date()
        appendLine("Ringomote Bluetooth diagnostics")
        appendLine("Generated: ${TIMESTAMP.format(now)}")
        appendLine()
        appendLine("[App]")
        appendLine("Version: ${appVersion()}")
        appendLine("Package: ${appContext.packageName}")
        appendLine()
        appendLine("[Device]")
        appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine()
        appendLine("[Bluetooth]")
        appendBluetooth()
        appendLine()
        appendLine("[Transport]")
        appendLine("State: ${transport.state.value}")
        appendLine()
        appendLine("[Paired devices]")
        val paired = transport.bondedDevices()
        if (paired.isEmpty()) appendLine("(none, or no permission)")
        paired.forEach { appendLine("${it.name ?: "(no name)"}  ${it.address}") }
        appendLine()
        appendLine("[Events, oldest first]")
        val events = eventLog.entries()
        if (events.isEmpty()) appendLine("(none yet)")
        events.forEach { appendLine("${TIMESTAMP.format(Date(it.timeMillis))}  ${it.message}") }
    }

    @SuppressLint("MissingPermission") // adapter.name is read only with BLUETOOTH_CONNECT
    private fun StringBuilder.appendBluetooth() {
        val pm = appContext.packageManager
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        appendLine("Adapter present: ${adapter != null}")
        appendLine("BLE feature: ${pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}")
        val connect = BluetoothPermissions.canConnect(appContext)
        appendLine("Permissions granted: ${BluetoothPermissions.allGranted(appContext)} (connect: $connect)")
        if (adapter == null) return
        appendLine("Enabled: ${adapter.isEnabled}")
        appendLine("State: ${adapterState(adapter.state)}")
        if (adapter.isEnabled) {
            appendLine("LE advertiser: ${adapter.bluetoothLeAdvertiser != null}")
            appendLine("Multiple advertisement: ${adapter.isMultipleAdvertisementSupported}")
            appendLine("LE 2M PHY: ${adapter.isLe2MPhySupported}")
        }
        if (connect) {
            val name = try {
                adapter.name
            } catch (_: SecurityException) {
                null
            }
            appendLine("Adapter name: ${name ?: "(unknown)"}")
        }
    }

    private fun appVersion(): String = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: PackageManager.NameNotFoundException) {
        "(unknown)"
    }

    private fun adapterState(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "off"
        BluetoothAdapter.STATE_TURNING_ON -> "turning on"
        BluetoothAdapter.STATE_ON -> "on"
        BluetoothAdapter.STATE_TURNING_OFF -> "turning off"
        else -> "unknown ($state)"
    }

    private companion object {
        /** Local time with milliseconds, so events can be lined up with `adb logcat`. */
        val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }
}
