package org.ghostsinthelab.app.ringomote.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import org.ghostsinthelab.app.ringomote.hid.HidReportDescriptor
import java.util.UUID

/**
 * The GATT layout of a HID over GATT (HOGP 1.0) keyboard, built from the HID report
 * descriptor, plus the values the host may read.
 *
 * Three primary services, in the order [pending] hands them out:
 * - Device Information (0x180A): Manufacturer Name (0x2A29) and PnP ID (0x2A50), readable
 *   without encryption; tvOS reads them before pairing.
 * - Battery (0x180F): Battery Level (0x2A19), notifiable; reported as 100 % because the host
 *   insists on it and the phone's real level is not the remote's concern.
 * - HID (0x1812): HID Information (0x2A4A), Report Map (0x2A4B, the descriptor), HID Control
 *   Point (0x2A4C), Protocol Mode (0x2A4E) and one input Report (0x2A4D) per report ID in the
 *   descriptor, each with a Report Reference descriptor (0x2908) naming its ID and a Client
 *   Characteristic Configuration descriptor (0x2902) the host writes to subscribe. HID
 *   attributes require an encrypted link, which is what makes the host pair.
 *
 * Values are kept here rather than in the (deprecated) characteristic value fields, so reads
 * are answered from one place. Verified against an Apple TV: it reads 0x2A4A, 0x2A4B and the
 * 0x2908 descriptors, then subscribes to both reports and the battery level.
 */
class HidGattServices(reportMap: ByteArray) {

    private val values = HashMap<Any, ByteArray>()
    private val inputReports = HashMap<Int, BluetoothGattCharacteristic>()
    private val inputReportCccds = HashSet<BluetoothGattDescriptor>()

    /** Services still to be added to the server; add one at a time, in this order. */
    val pending: ArrayDeque<BluetoothGattService> = ArrayDeque(
        listOf(deviceInformation(), battery(), hid(reportMap)),
    )

    /** The input report characteristic carrying report [id], or null for an unknown ID. */
    fun inputReport(id: Int): BluetoothGattCharacteristic? = inputReports[id]

    /** The value to answer a read of [characteristic] with. */
    fun valueOf(characteristic: BluetoothGattCharacteristic): ByteArray? = values[characteristic]

    /** The value to answer a read of [descriptor] with. */
    fun valueOf(descriptor: BluetoothGattDescriptor): ByteArray? = values[descriptor]

    /** Whether a write of [value] to [descriptor] turns on notifications for an input report. */
    fun isInputReportNotificationEnable(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        if (descriptor !in inputReportCccds) return false
        values[descriptor] = value.copyOf()
        return value.isNotEmpty() && value[0].toInt() and 0x01 != 0
    }

    private fun deviceInformation() = BluetoothGattService(DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(readable(MANUFACTURER_NAME, MANUFACTURER.toByteArray(Charsets.UTF_8), encrypted = false))
        addCharacteristic(readable(PNP_ID, PNP_ID_VALUE, encrypted = false))
    }

    private fun battery() = BluetoothGattService(BATTERY, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val level = BluetoothGattCharacteristic(
            BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        values[level] = byteArrayOf(100)
        level.addDescriptor(clientConfiguration())
        addCharacteristic(level)
    }

    private fun hid(reportMap: ByteArray) = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(readable(HID_INFORMATION, HID_INFORMATION_VALUE, encrypted = true))
        addCharacteristic(readable(REPORT_MAP, reportMap, encrypted = true))
        addCharacteristic(
            BluetoothGattCharacteristic(
                HID_CONTROL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        val protocolMode = BluetoothGattCharacteristic(
            PROTOCOL_MODE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        values[protocolMode] = byteArrayOf(PROTOCOL_MODE_REPORT)
        addCharacteristic(protocolMode)

        for ((id, size) in INPUT_REPORTS) {
            val report = BluetoothGattCharacteristic(
                REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            )
            values[report] = ByteArray(size)
            val reference = BluetoothGattDescriptor(REPORT_REFERENCE, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED)
            values[reference] = byteArrayOf(id.toByte(), REPORT_TYPE_INPUT)
            report.addDescriptor(reference)
            val cccd = clientConfiguration()
            inputReportCccds += cccd
            report.addDescriptor(cccd)
            inputReports[id] = report
            addCharacteristic(report)
        }
    }

    private fun readable(uuid: UUID, value: ByteArray, encrypted: Boolean) = BluetoothGattCharacteristic(
        uuid,
        BluetoothGattCharacteristic.PROPERTY_READ,
        if (encrypted) BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED else BluetoothGattCharacteristic.PERMISSION_READ,
    ).also { values[it] = value.copyOf() }

    private fun clientConfiguration() = BluetoothGattDescriptor(
        CLIENT_CONFIGURATION,
        BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
    ).also { values[it] = byteArrayOf(0, 0) }

    companion object {
        /** Bluetooth SIG 16-bit UUIDs, from the Assigned Numbers document. */
        private fun sig(id: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(id))

        val HID_SERVICE: UUID = sig(0x1812)
        val BATTERY: UUID = sig(0x180F)
        val DEVICE_INFORMATION: UUID = sig(0x180A)
        val HID_INFORMATION: UUID = sig(0x2A4A)
        val REPORT_MAP: UUID = sig(0x2A4B)
        val HID_CONTROL_POINT: UUID = sig(0x2A4C)
        val REPORT: UUID = sig(0x2A4D)
        val PROTOCOL_MODE: UUID = sig(0x2A4E)
        val BATTERY_LEVEL: UUID = sig(0x2A19)
        val PNP_ID: UUID = sig(0x2A50)
        val MANUFACTURER_NAME: UUID = sig(0x2A29)
        val CLIENT_CONFIGURATION: UUID = sig(0x2902)
        val REPORT_REFERENCE: UUID = sig(0x2908)

        const val MANUFACTURER = "Ringomote"

        /** Input report IDs and payload sizes, from the report descriptor. */
        val INPUT_REPORTS: Map<Int, Int> = mapOf(
            HidReportDescriptor.KEYBOARD_REPORT_ID to HidReportDescriptor.KEYBOARD_REPORT_SIZE,
            HidReportDescriptor.CONSUMER_REPORT_ID to HidReportDescriptor.CONSUMER_REPORT_SIZE,
        )

        /** HID Information: bcdHID 1.11, country code 0, flags: remote wake, normally connectable. */
        val HID_INFORMATION_VALUE: ByteArray = byteArrayOf(0x11, 0x01, 0x00, 0x03)

        /** PnP ID: vendor ID source 2 (USB-IF), vendor 0x1D6B, product 0xB146, version 0.1 (placeholder IDs). */
        val PNP_ID_VALUE: ByteArray = byteArrayOf(0x02, 0x6B, 0x1D, 0x46, 0xB1.toByte(), 0x01, 0x00)

        /** Report Protocol Mode (not Boot). */
        const val PROTOCOL_MODE_REPORT: Byte = 0x01

        /** Report Reference type for input reports. */
        const val REPORT_TYPE_INPUT: Byte = 0x01
    }
}
