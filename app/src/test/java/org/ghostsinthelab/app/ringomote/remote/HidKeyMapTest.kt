package org.ghostsinthelab.app.ringomote.remote

import org.ghostsinthelab.app.ringomote.hid.HidUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [HidKeyMap], the mapping from each [RemoteControl] to the HID usage sent to tvOS.
 *
 * Expected values are written as literals from the USB HID Usage Tables (Keyboard/Keypad page
 * 0x07, section 10; Consumer page 0x0C, section 15), not taken from the production constants,
 * so a typo in [org.ghostsinthelab.app.ringomote.hid.KeyboardUsage] or
 * [org.ghostsinthelab.app.ringomote.hid.ConsumerUsage] is caught here.
 */
class HidKeyMapTest {

    /** Expected usage per control, straight from the HID Usage Tables. */
    private val expected = mapOf(
        RemoteControl.Right to HidUsage.Key(0x4F), // Keyboard RightArrow
        RemoteControl.Left to HidUsage.Key(0x50), // Keyboard LeftArrow
        RemoteControl.Down to HidUsage.Key(0x51), // Keyboard DownArrow
        RemoteControl.Up to HidUsage.Key(0x52), // Keyboard UpArrow
        RemoteControl.Select to HidUsage.Key(0x28), // Keyboard Return (ENTER)
        RemoteControl.Menu to HidUsage.Key(0x29), // Keyboard ESCAPE
        RemoteControl.PlayPause to HidUsage.Consumer(0xCD), // Play/Pause
        RemoteControl.VolumeUp to HidUsage.Consumer(0xE9), // Volume Increment
        RemoteControl.VolumeDown to HidUsage.Consumer(0xEA), // Volume Decrement
        RemoteControl.Home to HidUsage.Consumer(0x223), // AC Home
    )

    /** Every RemoteControl has a mapping, and the expectation table above covers all of them. */
    @Test
    fun everyControlMaps() {
        assertEquals(RemoteControl.entries.toSet(), expected.keys)
        RemoteControl.entries.forEach { HidKeyMap.usageFor(it) }
    }

    /** Each control maps to the usage page and ID the HID Usage Tables give for its function. */
    @Test
    fun usagesMatchHidUsageTables() {
        RemoteControl.entries.forEach {
            assertEquals("Usage for $it", expected.getValue(it), HidKeyMap.usageFor(it))
        }
    }

    /** No two controls share a usage, so releasing one can never release another. */
    @Test
    fun usagesAreDistinct() {
        val usages = RemoteControl.entries.map(HidKeyMap::usageFor)
        assertEquals(usages.size, usages.toSet().size)
    }
}
