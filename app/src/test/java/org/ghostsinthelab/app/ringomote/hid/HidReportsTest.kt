package org.ghostsinthelab.app.ringomote.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the payload encoders in [HidReports] and the value semantics of [HidReport].
 *
 * Expected bytes are hand-encoded from the layouts declared in [HidReportDescriptor]: the
 * keyboard report is modifier byte, reserved byte, then six key slots (the boot-keyboard layout of
 * HID 1.11 Appendix B.1); the consumer report is one 16-bit usage in little-endian order, as HID
 * 1.11 section 5.8 requires for multi-byte fields. Usage IDs are from the HID Usage Tables.
 */
class HidReportsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Keys go in slots from index 2 in the given order; modifier and reserved bytes stay 0; unused slots are 0. */
    @Test
    fun keyboardReportLayout() {
        val report = HidReports.keyboard(listOf(KeyboardUsage.UP_ARROW, KeyboardUsage.ENTER))
        assertEquals(HidReportDescriptor.KEYBOARD_REPORT_ID, report.id)
        assertArrayEquals(bytes(0x00, 0x00, 0x52, 0x28, 0x00, 0x00, 0x00, 0x00), report.data)
    }

    /** A full report uses all six slots and still has KEYBOARD_REPORT_SIZE bytes. */
    @Test
    fun keyboardReportWithSixKeysFillsAllSlots() {
        val report = HidReports.keyboard(listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09))
        assertArrayEquals(bytes(0x00, 0x00, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09), report.data)
    }

    /** No keys gives the all-zero "everything released" report. */
    @Test
    fun emptyKeyboardReportIsAllZeros() {
        val report = HidReports.keyboard(emptyList())
        assertEquals(HidReportDescriptor.KEYBOARD_REPORT_ID, report.id)
        assertArrayEquals(ByteArray(HidReportDescriptor.KEYBOARD_REPORT_SIZE), report.data)
    }

    /** More keys than the descriptor's six slots is a programming error and is rejected. */
    @Test
    fun keyboardReportRejectsSevenKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            HidReports.keyboard(listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A))
        }
    }

    /** A 16-bit consumer usage is sent low byte first: AC Home 0x0223 becomes 23 02. */
    @Test
    fun consumerReportIsLittleEndian() {
        val report = HidReports.consumer(ConsumerUsage.AC_HOME)
        assertEquals(HidReportDescriptor.CONSUMER_REPORT_ID, report.id)
        assertArrayEquals(bytes(0x23, 0x02), report.data)
    }

    /** A one-byte usage (Play/Pause 0xCD) keeps a zero high byte, and the max usage 0x3FF encodes as FF 03. */
    @Test
    fun consumerReportEncodesSmallAndMaximumUsages() {
        assertArrayEquals(bytes(0xCD, 0x00), HidReports.consumer(ConsumerUsage.PLAY_PAUSE).data)
        assertArrayEquals(bytes(0xFF, 0x03), HidReports.consumer(HidReportDescriptor.CONSUMER_MAX_USAGE).data)
    }

    /** Usage 0 is the consumer "nothing pressed" report. */
    @Test
    fun consumerZeroIsRelease() {
        assertArrayEquals(ByteArray(HidReportDescriptor.CONSUMER_REPORT_SIZE), HidReports.consumer(0).data)
    }

    /** Usages outside 0..CONSUMER_MAX_USAGE are rejected, matching the descriptor's logical range. */
    @Test
    fun consumerReportRejectsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) { HidReports.consumer(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            HidReports.consumer(HidReportDescriptor.CONSUMER_MAX_USAGE + 1)
        }
    }

    /** idle(1) is the all-zero keyboard report. */
    @Test
    fun idleKeyboard() {
        assertEquals(HidReports.keyboard(emptyList()), HidReports.idle(HidReportDescriptor.KEYBOARD_REPORT_ID))
    }

    /** idle(2) is the all-zero consumer report. */
    @Test
    fun idleConsumer() {
        assertEquals(HidReports.consumer(0), HidReports.idle(HidReportDescriptor.CONSUMER_REPORT_ID))
    }

    /** idle() of an undeclared report ID is null, so GET_REPORT for it can be answered with an error. */
    @Test
    fun idleUnknownIsNull() {
        assertNull(HidReports.idle(0))
        assertNull(HidReports.idle(3))
    }

    /** Two reports are equal (with equal hash codes) when ID and payload match; ID or payload differences break equality. */
    @Test
    fun hidReportEquality() {
        val a = HidReport(2, bytes(0x23, 0x02))
        val b = HidReport(2, bytes(0x23, 0x02))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, HidReport(1, bytes(0x23, 0x02)))
        assertNotEquals(a, HidReport(2, bytes(0x23, 0x03)))
    }

    /** HidReport copies its input array and returns a fresh copy from [HidReport.data], so neither side can mutate it. */
    @Test
    fun hidReportDefensiveCopies() {
        val source = bytes(0x23, 0x02)
        val report = HidReport(2, source)
        source[0] = 0
        assertArrayEquals(bytes(0x23, 0x02), report.data)

        val out = report.data
        assertNotSame(out, report.data)
        out[0] = 0
        assertArrayEquals(bytes(0x23, 0x02), report.data)
    }

    /** toString shows the ID and payload as hex bytes, which is what log lines rely on. */
    @Test
    fun hidReportToString() {
        assertEquals("HidReport(id=2, data=23 02)", HidReports.consumer(ConsumerUsage.AC_HOME).toString())
    }
}
