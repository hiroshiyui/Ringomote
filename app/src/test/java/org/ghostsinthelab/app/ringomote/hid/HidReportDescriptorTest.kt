package org.ghostsinthelab.app.ringomote.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [HidReportDescriptor] by parsing its bytes, rather than comparing them to a fixed array.
 *
 * The test carries a minimal short-item parser written from the USB Device Class Definition for
 * HID 1.11, section 6.2.2 (Report Descriptor): each item starts with a prefix byte whose low two
 * bits give the data size (0, 1, 2 or 4 bytes), bits 2–3 the type (Main, Global, Local) and the
 * high nibble the tag. It tracks the global Report ID, Report Size, Report Count and Logical
 * Maximum, records every Main Input item, and counts Collection / End Collection. The expected
 * values come from the constants in [HidReportDescriptor] and the payload layouts that
 * [HidReports] encodes, so the descriptor, the constants and the encoders cannot drift apart.
 */
class HidReportDescriptorTest {

    /** One Main Input item with the global state in effect when it was declared. */
    private data class InputItem(
        val reportId: Int,
        val reportSize: Int,
        val reportCount: Int,
        val logicalMax: Int,
        val flags: Int,
    ) {
        val bits get() = reportSize * reportCount
        val isConstant get() = flags and 0x01 != 0
        val isVariable get() = flags and 0x02 != 0
    }

    /** What the parser found in a descriptor. */
    private data class Parsed(
        val inputs: List<InputItem>,
        val reportIds: Set<Int>,
        val outputOrFeatureItems: Int,
        val maxDepth: Int,
        val finalDepth: Int,
        val longItems: Int,
    )

    /**
     * Parses short items per HID 1.11 section 6.2.2.2. Long items (prefix 0xFE) are counted but
     * not decoded; Ringomote's descriptor should contain none. Fails on an End Collection
     * without a matching Collection or on a truncated item.
     */
    private fun parse(descriptor: ByteArray): Parsed {
        val inputs = mutableListOf<InputItem>()
        val reportIds = linkedSetOf<Int>()
        var reportId = 0
        var reportSize = 0
        var reportCount = 0
        var logicalMax = 0
        var outputOrFeature = 0
        var depth = 0
        var maxDepth = 0
        var longItems = 0
        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF
            if (prefix == 0xFE) {
                // Long item: bDataSize follows, then bLongItemTag, then the data.
                longItems++
                val size = descriptor[i + 1].toInt() and 0xFF
                i += 3 + size
                continue
            }
            val size = when (val s = prefix and 0x03) { 3 -> 4; else -> s }
            val type = (prefix shr 2) and 0x03
            val tag = prefix shr 4
            assertTrue("Truncated item at offset $i", i + 1 + size <= descriptor.size)
            // Item data is little-endian; unsigned and signed readings are both kept.
            var unsigned = 0L
            for (b in 0 until size) {
                unsigned = unsigned or ((descriptor[i + 1 + b].toLong() and 0xFF) shl (8 * b))
            }
            val signed = when (size) {
                1 -> unsigned.toByte().toInt()
                2 -> unsigned.toShort().toInt()
                4 -> unsigned.toInt()
                else -> 0
            }
            when (type) {
                0 -> when (tag) { // Main
                    0x8 -> inputs += InputItem(reportId, reportSize, reportCount, logicalMax, unsigned.toInt())
                    0x9, 0xB -> outputOrFeature++ // Output, Feature
                    0xA -> { depth++; maxDepth = maxOf(maxDepth, depth) } // Collection
                    0xC -> { depth--; assertTrue("End Collection without Collection at $i", depth >= 0) }
                }

                1 -> when (tag) { // Global
                    0x2 -> logicalMax = signed // Logical Maximum is signed (6.2.2.7)
                    0x7 -> reportSize = unsigned.toInt()
                    0x8 -> { reportId = unsigned.toInt(); reportIds += reportId }
                    0x9 -> reportCount = unsigned.toInt()
                }
            }
            i += 1 + size
        }
        return Parsed(inputs, reportIds, outputOrFeature, maxDepth, depth, longItems)
    }

    private val parsed = parse(HidReportDescriptor.bytes)

    /** Every Collection is closed, and there is at least one (the application collections). */
    @Test
    fun collectionsAreBalanced() {
        assertEquals(0, parsed.finalDepth)
        assertTrue(parsed.maxDepth >= 1)
    }

    /** The descriptor uses only short items, which is all the parser and most hosts expect. */
    @Test
    fun containsNoLongItems() {
        assertEquals(0, parsed.longItems)
    }

    /** Exactly the keyboard (1) and consumer (2) report IDs are declared, nothing else. */
    @Test
    fun declaresExactlyReportIdsOneAndTwo() {
        assertEquals(
            setOf(HidReportDescriptor.KEYBOARD_REPORT_ID, HidReportDescriptor.CONSUMER_REPORT_ID),
            parsed.reportIds,
        )
        assertEquals(1, HidReportDescriptor.KEYBOARD_REPORT_ID)
        assertEquals(2, HidReportDescriptor.CONSUMER_REPORT_ID)
    }

    /** Every Input item sits under a Report ID, so no data belongs to the implicit report 0. */
    @Test
    fun everyInputBelongsToADeclaredReport() {
        assertTrue(parsed.inputs.isNotEmpty())
        parsed.inputs.forEach { assertTrue("Input outside a report: $it", it.reportId in parsed.reportIds) }
    }

    /** The keyboard report's input bits add up to KEYBOARD_REPORT_SIZE bytes. */
    @Test
    fun keyboardInputBitsMatchReportSize() {
        val bits = parsed.inputs.filter { it.reportId == HidReportDescriptor.KEYBOARD_REPORT_ID }.sumOf { it.bits }
        assertEquals(8 * HidReportDescriptor.KEYBOARD_REPORT_SIZE, bits)
    }

    /** The consumer report's input bits add up to CONSUMER_REPORT_SIZE bytes. */
    @Test
    fun consumerInputBitsMatchReportSize() {
        val bits = parsed.inputs.filter { it.reportId == HidReportDescriptor.CONSUMER_REPORT_ID }.sumOf { it.bits }
        assertEquals(8 * HidReportDescriptor.CONSUMER_REPORT_SIZE, bits)
    }

    /** Keyboard layout: 8 one-bit modifiers, one constant reserved byte, then the key-slot array. */
    @Test
    fun keyboardInputLayoutIsBootKeyboardStyle() {
        val kb = parsed.inputs.filter { it.reportId == HidReportDescriptor.KEYBOARD_REPORT_ID }
        assertEquals(3, kb.size)
        val (modifiers, reserved, keys) = kb
        assertEquals(1 to 8, modifiers.reportSize to modifiers.reportCount)
        assertTrue(modifiers.isVariable && !modifiers.isConstant)
        assertEquals(8 to 1, reserved.reportSize to reserved.reportCount)
        assertTrue(reserved.isConstant)
        assertEquals(8 to HidReportDescriptor.KEYBOARD_MAX_KEYS, keys.reportSize to keys.reportCount)
        assertFalse(keys.isVariable || keys.isConstant)
    }

    /** The key-slot array's Logical Maximum is KEYBOARD_MAX_USAGE, the bound HidUsage.Key enforces. */
    @Test
    fun keyboardArrayLogicalMaxMatchesConstant() {
        val keys = parsed.inputs.single {
            it.reportId == HidReportDescriptor.KEYBOARD_REPORT_ID && !it.isVariable && !it.isConstant
        }
        assertEquals(HidReportDescriptor.KEYBOARD_MAX_USAGE, keys.logicalMax)
    }

    /** The consumer array's Logical Maximum is CONSUMER_MAX_USAGE, the bound HidUsage.Consumer enforces. */
    @Test
    fun consumerArrayLogicalMaxMatchesConstant() {
        val consumer = parsed.inputs.single { it.reportId == HidReportDescriptor.CONSUMER_REPORT_ID }
        assertFalse(consumer.isVariable || consumer.isConstant)
        assertEquals(16, consumer.reportSize)
        assertEquals(HidReportDescriptor.CONSUMER_MAX_USAGE, consumer.logicalMax)
    }

    /** No Output or Feature items, so the host has nothing to write back to the phone. */
    @Test
    fun declaresNoOutputOrFeatureItems() {
        assertEquals(0, parsed.outputOrFeatureItems)
    }

    /** [HidReportDescriptor.bytes] hands out a fresh copy; mutating it leaves the descriptor intact. */
    @Test
    fun bytesReturnsDefensiveCopy() {
        val first = HidReportDescriptor.bytes
        val original = first.copyOf()
        val second = HidReportDescriptor.bytes
        assertNotSame(first, second)
        first.fill(0x7F)
        assertArrayEquals(original, HidReportDescriptor.bytes)
    }
}
