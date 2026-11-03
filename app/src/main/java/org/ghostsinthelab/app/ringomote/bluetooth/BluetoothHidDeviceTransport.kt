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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Lifecycle: [start] opens the profile proxy and registers the app; Bluetooth turning off
 * drops both, and turning it back on re-registers automatically while started. Only one app
 * at a time can be registered as a HID device; if another one takes over, the state becomes
 * [TransportState.RegistrationFailed].
 *
 * Threading: Bluetooth callbacks run on a private single-thread executor or binder threads;
 * all shared fields are volatile and state is published through a [StateFlow].
 *
 * Permissions are checked with [BluetoothPermissions] before each guarded call; the lint
 * suppression covers those calls.
 */
@SuppressLint("MissingPermission")
class BluetoothHidDeviceTransport(context: Context) : HidTransport {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<TransportState>(TransportState.Stopped)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    @Volatile private var started = false
    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        SDP_NAME,
        SDP_DESCRIPTION,
        SDP_PROVIDER,
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        HidReportDescriptor.bytes,
    )

    @Synchronized
    override fun start() {
        if (!started) {
            started = true
            ContextCompat.registerReceiver(
                appContext,
                adapterStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        if (hidDevice == null) openProfile()
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        appContext.unregisterReceiver(adapterStateReceiver)
        closeProfile()
        _state.value = TransportState.Stopped
    }

    override fun bondedDevices(): List<HostDevice> {
        val adapter = adapter ?: return emptyList()
        if (!BluetoothPermissions.canConnect(appContext)) return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { it.toHostDevice() }
            .sortedBy { it.name ?: it.address }
    }

    override fun connect(address: String): Boolean {
        val proxy = hidDevice ?: return false
        val adapter = adapter ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return proxy.connect(adapter.getRemoteDevice(address))
    }

    override fun disconnect(): Boolean {
        val proxy = hidDevice ?: return false
        val device = host ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        return proxy.disconnect(device)
    }

    override fun send(report: HidReport): Boolean {
        val proxy = hidDevice ?: return false
        val device = host ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        return proxy.sendReport(device, report.id, report.data)
    }

    /** Opens the HID device profile proxy; registration continues in [profileListener]. */
    private fun openProfile() {
        val adapter = adapter
        _state.value = when {
            adapter == null -> TransportState.Unsupported
            !BluetoothPermissions.canConnect(appContext) -> TransportState.PermissionRequired
            !adapter.isEnabled -> TransportState.BluetoothOff
            !adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE) ->
                TransportState.Unsupported
            else -> TransportState.Registering
        }
    }

    private fun closeProfile() {
        val proxy = hidDevice ?: return
        hidDevice = null
        if (BluetoothPermissions.canConnect(appContext)) {
            host?.let { proxy.disconnect(it) }
            proxy.unregisterApp()
        }
        host = null
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val device = proxy as BluetoothHidDevice
            if (!started) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
                return
            }
            hidDevice = device
            val requested = BluetoothPermissions.canConnect(appContext) &&
                device.registerApp(sdpSettings, null, null, callbackExecutor, hidCallback)
            if (!requested) _state.value = TransportState.RegistrationFailed
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            host = null
            if (started) _state.value = TransportState.BluetoothOff
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!started) return
            if (!registered) {
                host = null
                _state.value = TransportState.RegistrationFailed
                return
            }
            _state.value = TransportState.Ready
            // A host we were virtually cabled to before: reconnect to it right away.
            if (pluggedDevice != null && BluetoothPermissions.canConnect(appContext)) {
                hidDevice?.connect(pluggedDevice)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (!started) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    _state.value = TransportState.Connected(device.toHostDevice())
                }

                BluetoothProfile.STATE_CONNECTING ->
                    _state.value = TransportState.Connecting(device.toHostDevice())

                else -> if (host == null || host == device) {
                    host = null
                    _state.value = TransportState.Ready
                }
            }
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
            if (host == device) host = null
            if (started) _state.value = TransportState.Ready
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> synchronized(this@BluetoothHidDeviceTransport) {
                    if (started && hidDevice == null) openProfile()
                }

                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    host = null
                    if (started) _state.value = TransportState.BluetoothOff
                }
            }
        }
    }

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
