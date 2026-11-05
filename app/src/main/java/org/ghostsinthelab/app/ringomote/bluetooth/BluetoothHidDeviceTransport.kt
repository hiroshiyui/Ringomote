package org.ghostsinthelab.app.ringomote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportDescriptor
import org.ghostsinthelab.app.ringomote.hid.HidReports
import java.util.concurrent.Executors

/**
 * [HidTransport] over classic Bluetooth, using Android's HID device profile
 * ([BluetoothHidDevice], API 28+).
 *
 * Classic HID was chosen over BLE HID over GATT because it is the platform's supported way for
 * an app to act as a HID device, and tvOS accepts it like any Bluetooth keyboard. The phone is
 * shown to the Apple TV under the phone's own Bluetooth name, not the SDP name below.
 *
 * This class only adapts Android's Bluetooth API: it forwards platform callbacks to a
 * [HidLinkController], which owns the connection state machine and its recovery rules, and
 * performs the controller's requests through [HidProfilePort]. Only one app at a time can be
 * registered as a HID device; if another one takes over, the state becomes
 * [TransportState.RegistrationFailed] and [start] retries.
 *
 * Permissions are checked (via [BluetoothPermissions]) before each guarded call. Lint cannot
 * see those checks through [HidProfilePort], so `MissingPermission` is suppressed only on the
 * members that make guarded calls; anything new outside them is still checked by lint.
 */
class BluetoothHidDeviceTransport(context: Context) : HidTransport {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    /** The open profile proxy; set and cleared only by [port] and [profileListener]. */
    @Volatile private var hidDevice: BluetoothHidDevice? = null

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        SDP_NAME,
        SDP_DESCRIPTION,
        SDP_PROVIDER,
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        HidReportDescriptor.bytes,
    )

    // Every call here is reached only through HidLinkController, which checks hasPermission().
    @SuppressLint("MissingPermission")
    private val port: HidProfilePort = object : HidProfilePort {
        override val hasAdapter get() = adapter != null
        override fun hasPermission() = BluetoothPermissions.canConnect(appContext)
        override fun isBluetoothOn() = adapter?.isEnabled == true

        override fun openProxy(): Boolean =
            adapter?.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE) == true

        override fun closeProxy() {
            val proxy = hidDevice ?: return
            hidDevice = null
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        }

        override fun registerApp(): Boolean =
            hidDevice?.registerApp(sdpSettings, null, null, callbackExecutor, hidCallback) == true

        override fun unregisterApp() {
            if (hasPermission()) hidDevice?.unregisterApp()
        }

        override fun connect(address: String): Boolean =
            remoteDevice(address)?.let { hidDevice?.connect(it) } == true

        override fun disconnect(address: String): Boolean =
            hasPermission() && remoteDevice(address)?.let { hidDevice?.disconnect(it) } == true

        override fun hostDevice(address: String): HostDevice =
            remoteDevice(address)?.toHostDevice() ?: HostDevice(address, null)
    }

    private val controller = HidLinkController(port)

    override val state: StateFlow<TransportState> = controller.state

    private var receiverRegistered = false

    @Synchronized
    override fun start() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        controller.start()
    }

    @Synchronized
    override fun stop() {
        if (receiverRegistered) {
            appContext.unregisterReceiver(adapterStateReceiver)
            receiverRegistered = false
        }
        controller.stop()
    }

    @SuppressLint("MissingPermission") // checked by canConnect() below
    override fun bondedDevices(): List<HostDevice> {
        val adapter = adapter ?: return emptyList()
        if (!BluetoothPermissions.canConnect(appContext)) return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { it.toHostDevice() }
            .sortedBy { it.name ?: it.address }
    }

    /** Classic HID can dial a bonded host; kept for this alternative transport, unused by the UI. */
    fun connect(address: String): Boolean =
        BluetoothAdapter.checkBluetoothAddress(address) && controller.connect(address)

    override fun disconnect(): Boolean = controller.disconnect()

    @SuppressLint("MissingPermission") // checked by canConnect() below
    override fun send(report: HidReport): Boolean {
        val proxy = hidDevice ?: return false
        val device = controller.connectedHost?.let(::remoteDevice) ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        return proxy.sendReport(device, report.id, report.data)
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        if (BluetoothAdapter.checkBluetoothAddress(address)) adapter?.getRemoteDevice(address) else null

    private val profileListener: BluetoothProfile.ServiceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as BluetoothHidDevice
            hidDevice = device
            if (!controller.onProxyConnected()) {
                // Not wanted any more (stopped meanwhile): close this proxy, not a newer one.
                if (hidDevice === device) hidDevice = null
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            controller.onProxyDisconnected()
        }
    }

    // Callbacks only arrive for an app registered with BLUETOOTH_CONNECT granted.
    @SuppressLint("MissingPermission")
    private val hidCallback: BluetoothHidDevice.Callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            controller.onAppStatusChanged(registered, pluggedDevice?.address)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val change = when (state) {
                BluetoothProfile.STATE_CONNECTED -> ConnectionChange.Connected
                BluetoothProfile.STATE_CONNECTING -> ConnectionChange.Connecting
                else -> ConnectionChange.Disconnected
            }
            controller.onConnectionStateChanged(device.address, change)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val proxy = hidDevice ?: return
            val idle = HidReports.idle(id.toInt())
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && idle != null) {
                proxy.replyReport(device, type, id, idle.data)
            } else {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // No output or feature reports are declared; acknowledge so the host does not wait.
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            controller.onConnectionStateChanged(device.address, ConnectionChange.Disconnected)
        }
    }

    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> controller.onBluetoothOn()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> controller.onBluetoothOff()
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by canConnect() below
    private fun BluetoothDevice.toHostDevice(): HostDevice {
        val name = try {
            if (BluetoothPermissions.canConnect(appContext)) name else null
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read device name", e)
            null
        }
        return HostDevice(address, name)
    }

    private companion object {
        const val TAG = "BluetoothHidTransport"
        const val SDP_NAME = "Ringomote"
        const val SDP_DESCRIPTION = "Remote control"
        const val SDP_PROVIDER = "Ringomote"
    }
}
