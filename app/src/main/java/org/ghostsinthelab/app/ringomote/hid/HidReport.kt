package org.ghostsinthelab.app.ringomote.hid

/**
 * An input report ready to send: the report [id] and its payload [data] without the ID byte,
 * which is the form `BluetoothHidDevice.sendReport` takes.
 */
class HidReport(val id: Int, data: ByteArray) {
    private val payload = data.copyOf()

    /** The payload; a fresh copy each call. */
    val data: ByteArray get() = payload.copyOf()

    override fun equals(other: Any?) =
        other is HidReport && other.id == id && other.payload.contentEquals(payload)

    override fun hashCode() = 31 * id + payload.contentHashCode()

    override fun toString() = "HidReport(id=$id, data=${payload.joinToString(" ") { "%02X".format(it) }})"
}

/** Builds payloads that match the layouts declared in [HidReportDescriptor]. */
object HidReports {

    /**
     * The keyboard report with [keys] (keyboard usage IDs) held and no modifiers.
     * An empty collection gives the all-zero "everything released" report. Usages must be
     * distinct and inside the descriptor's range (1..[HidReportDescriptor.KEYBOARD_MAX_USAGE]).
     */
    fun keyboard(keys: Collection<Int>): HidReport {
        require(keys.size <= HidReportDescriptor.KEYBOARD_MAX_KEYS) { "At most 6 keys at once" }
        require(keys.all { it in 1..HidReportDescriptor.KEYBOARD_MAX_USAGE }) { "Keyboard usage out of range: $keys" }
        require(keys.toSet().size == keys.size) { "Duplicate keys: $keys" }
        val data = ByteArray(HidReportDescriptor.KEYBOARD_REPORT_SIZE)
        // data[0] = modifiers, data[1] = reserved; key slots start at index 2.
        keys.forEachIndexed { i, usage -> data[2 + i] = usage.toByte() }
        return HidReport(HidReportDescriptor.KEYBOARD_REPORT_ID, data)
    }

    /** The consumer report holding [usage]; `0` releases whatever was pressed. */
    fun consumer(usage: Int): HidReport {
        require(usage in 0..HidReportDescriptor.CONSUMER_MAX_USAGE) { "Consumer usage out of range: $usage" }
        val data = byteArrayOf((usage and 0xFF).toByte(), (usage shr 8 and 0xFF).toByte())
        return HidReport(HidReportDescriptor.CONSUMER_REPORT_ID, data)
    }

    /** An all-zero report for [reportId], used to answer a host's GET_REPORT; null if unknown. */
    fun idle(reportId: Int): HidReport? = when (reportId) {
        HidReportDescriptor.KEYBOARD_REPORT_ID -> keyboard(emptyList())
        HidReportDescriptor.CONSUMER_REPORT_ID -> consumer(0)
        else -> null
    }
}

/** Where encoded reports go: the Bluetooth transport in the app, a recorder in tests. */
fun interface HidReportSink {
    /** Sends [report]; returns false if it could not be sent (e.g. no host connected). */
    fun send(report: HidReport): Boolean
}
