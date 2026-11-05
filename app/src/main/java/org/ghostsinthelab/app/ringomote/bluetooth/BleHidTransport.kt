package org.ghostsinthelab.app.ringomote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import org.ghostsinthelab.app.ringomote.diagnostics.EventLog
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportDescriptor
import org.ghostsinthelab.app.ringomote.hid.HidReports
import java.util.UUID

/**
 * [HidTransport] over Bluetooth Low Energy: HID over GATT (HOGP), with the phone as the
 * peripheral and the Apple TV as the central.
 *
 * This replaced classic Bluetooth HID after a hardware check: tvOS lists BLE HID devices but
 * filters out classic devices whose class of device says "phone", which Android does not let
 * an app change. With BLE the phone advertises the HID service UUID and tvOS lists it under
 * Settings > Remotes and Devices > Bluetooth; pairing is a consent prompt followed by a 6-digit
 * comparison, and the host then reads the report map and subscribes to the input reports.
 *
 * This class only adapts Android's GATT server and advertiser: it forwards platform callbacks
 * to a [BleLinkController], which owns the connection state machine, and performs the
 * controller's requests through [BlePeripheralPort]. The GATT layout is built by
 * [HidGattServices]. Callbacks arrive on binder threads.
 *
 * Permissions are checked (via [BluetoothPermissions]) before each guarded call. Lint cannot
 * see those checks through [BlePeripheralPort], so `MissingPermission` is suppressed only on
 * the members that make guarded calls.
 *
 * Every platform event that matters for diagnosing a link is also recorded in [eventLog],
 * which the diagnostics report in Settings prints.
 */
class BleHidTransport(
    context: Context,
    private val eventLog: EventLog = EventLog(),
) : HidTransport {

    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    /** The open server and the services it holds; set and cleared only through [port]. */
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var services: HidGattServices? = null

    /** Handles of the services added to the current server, in order. */
    private val addedHandles = mutableListOf<Int>()

    /** For the controller's short timers; the main looper is fine for them. */
    private val timer = android.os.Handler(android.os.Looper.getMainLooper())

    /** Where the layout a host subscribed to is remembered across restarts. */
    private val layoutPrefs = appContext.getSharedPreferences("ble_layout", Context.MODE_PRIVATE)

    // Every call here is reached only through BleLinkController, which checks hasPermission().
    @SuppressLint("MissingPermission")
    private val port: BlePeripheralPort = object : BlePeripheralPort {
        override val hasAdapter get() = adapter != null && appContext.packageManager.hasSystemFeature(FEATURE_BLE)
        override fun hasPermission() = BluetoothPermissions.allGranted(appContext)
        override fun isBluetoothOn() = adapter?.isEnabled == true

        override fun openServer(): Boolean {
            val opened = manager?.openGattServer(appContext, serverCallback)
            if (opened == null) {
                eventLog.record("GATT server: open refused")
                return false
            }
            val built = HidGattServices(HidReportDescriptor.bytes)
            server = opened
            services = built
            addedHandles.clear()
            eventLog.record("GATT server: opened")
            return true
        }

        override fun addServices(): Boolean {
            val open = server ?: return false
            val built = services ?: return false
            eventLog.record("GATT server: adding services")
            // Services are added one at a time; onServiceAdded adds the next.
            return open.addService(built.pending.removeFirst())
        }

        override fun after(delayMillis: Long, action: () -> Unit) {
            timer.postDelayed(action, delayMillis)
        }

        override fun closeServer() {
            val open = server ?: return
            server = null
            services = null
            open.close()
            eventLog.record("GATT server: closed")
        }

        override fun startAdvertising(): Boolean {
            val advertiser = adapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                eventLog.record("Advertising: no LE advertiser")
                return false
            }
            // The platform advertiser keeps our callback on its list until stopAdvertising is
            // called with it, even across Bluetooth off/on, when the adapter gave us no chance
            // to call stop (it is already gone); a start would then fail with
            // ADVERTISE_FAILED_ALREADY_STARTED for good. Clear a leftover entry first.
            if (advertiseRequested) {
                advertiser.stopAdvertising(advertiseCallback)
                eventLog.record("Advertising: cleared a stale registration")
            }
            advertiseRequested = true
            advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
            eventLog.record("Advertising: start requested")
            return true
        }

        override fun stopAdvertising() {
            val advertiser = adapter?.bluetoothLeAdvertiser
            if (advertiser != null) {
                advertiser.stopAdvertising(advertiseCallback)
                advertiseRequested = false
            }
            eventLog.record("Advertising: stopped")
        }

        override fun disconnect(address: String) {
            val device = remoteDevice(address) ?: return
            if (hasPermission()) server?.cancelConnection(device)
            eventLog.record("Disconnect from $address: requested")
        }

        override fun isBonded(address: String): Boolean =
            remoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED

        override fun hostDevice(address: String): HostDevice =
            remoteDevice(address)?.toHostDevice() ?: HostDevice(address, null)

        override fun pairedLayout(): List<Int>? =
            layoutPrefs.getString(KEY_LAYOUT, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() }

        override fun savePairedLayout(handles: List<Int>) {
            layoutPrefs.edit().putString(KEY_LAYOUT, handles.joinToString(",")).apply()
            eventLog.record("Layout: host subscribed at handles $handles, saved")
        }

        override fun dropLink(address: String) {
            val device = remoteDevice(address) ?: return
            eventLog.record("Drop link $address: opening a client connection to close it")
            // Over LE explicitly: the TV is dual-mode, and the default transport would dial
            // a classic link instead of attaching to the LE one that exists.
            device.connectGatt(
                appContext, false,
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        eventLog.record("Drop link $address: client ${connectionState(newState)}, status $status")
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.disconnect()
                            BluetoothProfile.STATE_DISCONNECTED -> gatt.close()
                        }
                    }
                },
                BluetoothDevice.TRANSPORT_LE,
            )
        }

        override fun noteLayoutMismatch(expected: List<Int>, actual: List<Int>) {
            eventLog.record("Layout: services at $actual, host last subscribed at $expected; it must re-check on a fresh link")
        }
    }

    private val controller = BleLinkController(port)

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

    override fun disconnect(): Boolean = controller.disconnect()

    @SuppressLint("MissingPermission") // checked by canConnect() below
    override fun send(report: HidReport): Boolean {
        val server = server ?: return false
        val characteristic = services?.inputReport(report.id) ?: return false
        val device = controller.connectedHost?.let(::remoteDevice) ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, report.data) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = report.data
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        // Only failures are logged: successes would flood the log with every key press.
        if (!sent) eventLog.record("Notify report ${report.id}: failed")
        return sent
    }

    private fun remoteDevice(address: String): BluetoothDevice? =
        if (BluetoothAdapter.checkBluetoothAddress(address)) adapter?.getRemoteDevice(address) else null

    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .setTimeout(0)
        .build()

    /** The HID service UUID is what tvOS looks for; the name is what it shows. */
    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(true)
        .addServiceUuid(ParcelUuid(HidGattServices.HID_SERVICE))
        .build()

    /**
     * Whether [advertiseCallback] was handed to the platform advertiser without a matching
     * stop since; see [BlePeripheralPort.startAdvertising] above.
     */
    @Volatile
    private var advertiseRequested = false

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            eventLog.record("Advertising: started")
            controller.onAdvertisingStarted()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed: $errorCode")
            eventLog.record("Advertising: failed, error $errorCode")
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                // The platform still lists the callback as active; a stop frees it, and the
                // controller's next attempt (Retry, or Bluetooth off/on) can then succeed.
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                advertiseRequested = false
            }
            controller.onAdvertisingFailed()
        }
    }

    // Callbacks only arrive for a server opened with BLUETOOTH_CONNECT granted.
    @SuppressLint("MissingPermission")
    private val serverCallback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val open = server ?: return
            val built = services ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Adding service ${service.uuid} failed: $status")
                eventLog.record("Service ${service.uuid.short()}: add failed, status $status")
                controller.onServicesFailed()
                return
            }
            eventLog.record("Service ${service.uuid.short()}: added, instance ${service.instanceId}")
            addedHandles += service.instanceId
            val next = built.pending.removeFirstOrNull()
            if (next == null) {
                eventLog.record("GATT server: all services added")
                controller.onServicesAdded(addedHandles.toList())
            } else if (!open.addService(next)) {
                eventLog.record("Service ${next.uuid.short()}: add refused")
                controller.onServicesFailed()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // No address: the status (HCI reason) and state are enough to diagnose link drops.
            Log.i(TAG, "Host connection state=$newState status=$status")
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            eventLog.record("Host ${device.address}: ${connectionState(newState)}, status $status, bonded $bonded")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> controller.onHostConnected(device.address)
                BluetoothProfile.STATE_DISCONNECTED -> controller.onHostDisconnected(device.address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = services?.valueOf(characteristic) ?: ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.from(offset))
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val value = services?.valueOf(descriptor) ?: ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.from(offset))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val uuid = descriptor.characteristic?.uuid?.short() ?: "?"
            eventLog.record("Host ${device.address}: wrote descriptor ${descriptor.uuid.short()} of $uuid = ${value.hex()}")
            if (services?.isInputReportNotificationEnable(descriptor, value) == true) {
                controller.onHostSubscribed(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            // Protocol Mode and HID Control Point writes: boot protocol and suspend are not
            // supported, so they are acknowledged and otherwise ignored.
            eventLog.record("Host ${device.address}: wrote ${characteristic.uuid.short()} = ${value.hex()}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    private val adapterStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    eventLog.record("Bluetooth: on")
                    controller.onBluetoothOn()
                }

                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    eventLog.record("Bluetooth: off")
                    controller.onBluetoothOff()
                }
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

    private fun ByteArray.from(offset: Int): ByteArray =
        if (offset <= 0) this else copyOfRange(minOf(offset, size), size)

    /** The 16-bit form of a Bluetooth SIG UUID (`0x1812`), or the full UUID otherwise. */
    private fun UUID.short(): String {
        val full = toString()
        return if (full.endsWith(BASE_UUID_SUFFIX) && full.startsWith("0000")) "0x" + full.substring(4, 8).uppercase() else full
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    private fun connectionState(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "state $state"
    }

    private companion object {
        private const val KEY_LAYOUT = "handles"
        const val TAG = "BleHidTransport"
        const val FEATURE_BLE = "android.hardware.bluetooth_le"
        const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    }
}
