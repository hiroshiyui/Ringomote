package org.ghostsinthelab.app.ringomote.network.companion

import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Companion Link button table to pyatv's `HidCommand` values (the codes the iOS
 * Remote sends) and checks the [RemoteControl] mapping: every control has a command except
 * Mute, which the protocol lacks.
 */
class CompanionKeyMapTest {

    /** The codes come from pyatv's `HidCommand`; Wake = 13 is verified on hardware. */
    @Test
    fun codesMatchPyatv() {
        val expected = mapOf(
            CompanionCommand.Up to 1, CompanionCommand.Down to 2, CompanionCommand.Left to 3,
            CompanionCommand.Right to 4, CompanionCommand.Menu to 5, CompanionCommand.Select to 6,
            CompanionCommand.Home to 7, CompanionCommand.VolumeUp to 8, CompanionCommand.VolumeDown to 9,
            CompanionCommand.Siri to 10, CompanionCommand.Screensaver to 11, CompanionCommand.Sleep to 12,
            CompanionCommand.Wake to 13, CompanionCommand.PlayPause to 14, CompanionCommand.ChannelUp to 15,
            CompanionCommand.ChannelDown to 16, CompanionCommand.Guide to 17, CompanionCommand.PageUp to 18,
            CompanionCommand.PageDown to 19,
        )
        assertEquals(expected.size, CompanionCommand.entries.size)
        expected.forEach { (command, code) -> assertEquals(command.name, code, command.code) }
    }

    /** Every remote control maps to a distinct command, except Mute. */
    @Test
    fun everyControlButMuteHasACommand() {
        val mapped = RemoteControl.entries.filter { it != RemoteControl.Mute }.map { CompanionKeyMap.commandFor(it) }
        assertTrue(mapped.none { it == null })
        assertEquals(mapped.size, mapped.toSet().size)
        assertNull(CompanionKeyMap.commandFor(RemoteControl.Mute))
        assertFalse(CompanionKeyMap.supports(RemoteControl.Mute))
        assertTrue(CompanionKeyMap.supports(RemoteControl.Home))
    }

    /** The arrows and Select are what the touch surface sends; they must be the HID arrows. */
    @Test
    fun touchSurfaceControlsAreArrows() {
        assertEquals(CompanionCommand.Up, CompanionKeyMap.commandFor(RemoteControl.Up))
        assertEquals(CompanionCommand.Select, CompanionKeyMap.commandFor(RemoteControl.Select))
        assertEquals(CompanionCommand.Menu, CompanionKeyMap.commandFor(RemoteControl.Menu))
    }
}
