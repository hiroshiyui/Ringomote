package org.ghostsinthelab.app.ringomote.remote

import org.ghostsinthelab.app.ringomote.hid.ConsumerUsage
import org.ghostsinthelab.app.ringomote.hid.HidUsage
import org.ghostsinthelab.app.ringomote.hid.KeyboardUsage

/**
 * The controls of the Siri Remote (1st generation) that Ringomote reproduces.
 *
 * Swipes on the touch surface become [Up], [Down], [Left] and [Right]; clicking it is
 * [Select]. Siri voice input is not reproduced.
 */
enum class RemoteControl {
    Up, Down, Left, Right, Select, Menu, Home, PlayPause, VolumeUp, VolumeDown,
}

/**
 * Maps each [RemoteControl] to the HID usage tvOS understands for it.
 *
 * Arrows, Enter and Escape follow how tvOS handles a Bluetooth keyboard (Escape acts as
 * Menu/Back). Home and media keys use the Consumer page.
 * TODO: confirm Home (AC Home) on a real Apple TV; fall back to Consumer Menu if needed.
 */
object HidKeyMap {
    fun usageFor(control: RemoteControl): HidUsage = when (control) {
        RemoteControl.Up -> HidUsage.Key(KeyboardUsage.UP_ARROW)
        RemoteControl.Down -> HidUsage.Key(KeyboardUsage.DOWN_ARROW)
        RemoteControl.Left -> HidUsage.Key(KeyboardUsage.LEFT_ARROW)
        RemoteControl.Right -> HidUsage.Key(KeyboardUsage.RIGHT_ARROW)
        RemoteControl.Select -> HidUsage.Key(KeyboardUsage.ENTER)
        RemoteControl.Menu -> HidUsage.Key(KeyboardUsage.ESCAPE)
        RemoteControl.Home -> HidUsage.Consumer(ConsumerUsage.AC_HOME)
        RemoteControl.PlayPause -> HidUsage.Consumer(ConsumerUsage.PLAY_PAUSE)
        RemoteControl.VolumeUp -> HidUsage.Consumer(ConsumerUsage.VOLUME_INCREMENT)
        RemoteControl.VolumeDown -> HidUsage.Consumer(ConsumerUsage.VOLUME_DECREMENT)
    }
}
