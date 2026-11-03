package org.ghostsinthelab.app.ringomote.hid

/**
 * The HID report descriptor Ringomote registers with the Bluetooth stack.
 *
 * It declares two input reports, a layout that tvOS accepts from ordinary Bluetooth keyboards:
 *
 * - Report [KEYBOARD_REPORT_ID]: a boot-keyboard-style report of [KEYBOARD_REPORT_SIZE] bytes:
 *   modifier bitmap, one reserved byte, then up to six pressed key usages (array).
 * - Report [CONSUMER_REPORT_ID]: one 16-bit consumer usage of [CONSUMER_REPORT_SIZE] bytes,
 *   little-endian, `0` meaning "nothing pressed".
 *
 * No output or feature reports are declared, so the host has nothing to write back (keyboard
 * LEDs, for example). [HidReports] builds the matching payloads; the unit tests parse this
 * descriptor and check that the sizes agree.
 */
object HidReportDescriptor {
    const val KEYBOARD_REPORT_ID = 1
    const val CONSUMER_REPORT_ID = 2

    /** Payload size of the keyboard report, excluding the report ID byte. */
    const val KEYBOARD_REPORT_SIZE = 8

    /** Payload size of the consumer report, excluding the report ID byte. */
    const val CONSUMER_REPORT_SIZE = 2

    /** Number of key slots in the keyboard report (classic 6-key rollover). */
    const val KEYBOARD_MAX_KEYS = 6

    /** Highest keyboard usage the descriptor allows (Keyboard Application, 0x65). */
    const val KEYBOARD_MAX_USAGE = 0x65

    /** Highest consumer usage the descriptor allows. */
    const val CONSUMER_MAX_USAGE = 0x03FF

    private val DESCRIPTOR = bytes(
        // --- Keyboard ---
        0x05, 0x01,        // Usage Page (Generic Desktop)
        0x09, 0x06,        // Usage (Keyboard)
        0xA1, 0x01,        // Collection (Application)
        0x85, KEYBOARD_REPORT_ID, //   Report ID (1)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,        //   Usage Minimum (Left Control)
        0x29, 0xE7,        //   Usage Maximum (Right GUI)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, 0x01,        //   Logical Maximum (1)
        0x75, 0x01,        //   Report Size (1)
        0x95, 0x08,        //   Report Count (8)
        0x81, 0x02,        //   Input (Data, Variable, Absolute)  ; modifier bitmap
        0x75, 0x08,        //   Report Size (8)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x01,        //   Input (Constant)                  ; reserved byte
        0x75, 0x08,        //   Report Size (8)
        0x95, KEYBOARD_MAX_KEYS, //   Report Count (6)
        0x15, 0x00,        //   Logical Minimum (0)
        0x25, KEYBOARD_MAX_USAGE, //   Logical Maximum (101)
        0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,        //   Usage Minimum (0)
        0x29, KEYBOARD_MAX_USAGE, //   Usage Maximum (101)
        0x81, 0x00,        //   Input (Data, Array, Absolute)     ; key slots
        0xC0,              // End Collection

        // --- Consumer control ---
        0x05, 0x0C,        // Usage Page (Consumer)
        0x09, 0x01,        // Usage (Consumer Control)
        0xA1, 0x01,        // Collection (Application)
        0x85, CONSUMER_REPORT_ID, //   Report ID (2)
        0x15, 0x00,        //   Logical Minimum (0)
        0x26, 0xFF, 0x03,  //   Logical Maximum (1023)
        0x19, 0x00,        //   Usage Minimum (0)
        0x2A, 0xFF, 0x03,  //   Usage Maximum (1023)
        0x75, 0x10,        //   Report Size (16)
        0x95, 0x01,        //   Report Count (1)
        0x81, 0x00,        //   Input (Data, Array, Absolute)
        0xC0,              // End Collection
    )

    /** The descriptor bytes; a fresh copy each call, so callers cannot alter the original. */
    val bytes: ByteArray get() = DESCRIPTOR.copyOf()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
