package org.ghostsinthelab.app.ringomote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportDescriptor
import org.ghostsinthelab.app.ringomote.hid.HidReports

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
 */
class BleHidTransport(context: Context) : HidTransport {

    private val appContext = context.applicationContext
    private val manager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter

    /** The open server and the services it holds; set and cleared only through [port]. */
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var services: HidGattServices? = null

    // Every call here is reached only through BleLinkController, which checks hasPermission().
    @SuppressLint("MissingPermission")
    private val port: BlePeripheralPort = object : BlePeripheralPort {
        override val hasAdapter get() = adapter != null && appContext.packageManager.hasSystemFeature(FEATURE_BLE)
        override fun hasPermission() = BluetoothPermissions.allGranted(appContext)
        override fun isBluetoothOn() = adapter?.isEnabled == true

        override fun openServer(): Boolean {
            val opened = manager?.openGattServer(appContext, serverCallback) ?: return false
            val built = HidGattServices(HidReportDescriptor.bytes)
            server = opened
            services = built
            // Services are added one at a time; onServiceAdded adds the next.
            return opened.addService(built.pending.removeFirst())
        }

        override fun closeServer() {
            val open = server ?: return
            server = null
            services = null
            open.close()
        }

        override fun startAdvertising(): Boolean {
            val advertiser = adapter?.bluetoothLeAdvertiser ?: return false
            advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
            return true
        }

        override fun stopAdvertising() {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        }

        override fun connect(address: String): Boolean {
            val device = remoteDevice(address) ?: return false
            return server?.connect(device, false) == true
        }

        override fun disconnect(address: String) {
            val device = remoteDevice(address) ?: return
            if (hasPermission()) server?.cancelConnection(device)
        }

        override fun isBonded(address: String): Boolean =
            remoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED

        override fun hostDevice(address: String): HostDevice =
            remoteDevice(address)?.toHostDevice() ?: HostDevice(address, null)
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

    override fun connect(address: String): Boolean =
        BluetoothAdapter.checkBluetoothAddress(address) && controller.connect(address)

    override fun disconnect(): Boolean = controller.disconnect()

    @SuppressLint("MissingPermission") // checked by canConnect() below
    override fun send(report: HidReport): Boolean {
        val server = server ?: return false
        val characteristic = services?.inputReport(report.id) ?: return false
        val device = controller.connectedHost?.let(::remoteDevice) ?: return false
        if (!BluetoothPermissions.canConnect(appContext)) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, report.data) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = report.data
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
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

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = controller.onAdvertisingStarted()

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed: $errorCode")
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
                controller.onServicesFailed()
                return
            }
            val next = built.pending.removeFirstOrNull()
            if (next == null) {
                controller.onServicesAdded()
            } else if (!open.addService(next)) {
                controller.onServicesFailed()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
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
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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

    private fun ByteArray.from(offset: Int): ByteArray =
        if (offset <= 0) this else copyOfRange(minOf(offset, size), size)

    private companion object {
        const val TAG = "BleHidTransport"
        const val FEATURE_BLE = "android.hardware.bluetooth_le"
    }
}
