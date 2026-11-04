package org.ghostsinthelab.app.ringomote.remote

import org.ghostsinthelab.app.ringomote.hid.ConsumerUsage
import org.ghostsinthelab.app.ringomote.hid.HidUsage
import org.ghostsinthelab.app.ringomote.hid.KeyboardUsage

/**
 * The controls of the Siri Remote (1st generation) that Ringomote reproduces, plus [Mute]
 * from the 2nd generation.
 *
 * Swipes on the touch surface become [Up], [Down], [Left] and [Right]; clicking it is
 * [Select]. Siri voice input is not reproduced.
 */
enum class RemoteControl {
    Up, Down, Left, Right, Select, Menu, Home, PlayPause, VolumeUp, VolumeDown, Mute,
}

/**
 * Maps each [RemoteControl] to the HID usage tvOS understands for it.
 *
 * Arrows, Enter and Escape follow how tvOS handles a Bluetooth keyboard (Escape acts as
 * Menu/Back). Home and media keys use the Consumer page. Home is not AC Home (0x223) but
 * Data On Screen (0x60), the 2nd-generation Siri Remote's TV button: tvOS treats a tap of it
 * as Home and a hold as Control Center, which AC Home never does. Mute (0xE2) is what the
 * 2nd-generation remote's Mute button sends. All verified on hardware.
 */
object HidKeyMap {
    fun usageFor(control: RemoteControl): HidUsage = when (control) {
        RemoteControl.Up -> HidUsage.Key(KeyboardUsage.UP_ARROW)
        RemoteControl.Down -> HidUsage.Key(KeyboardUsage.DOWN_ARROW)
        RemoteControl.Left -> HidUsage.Key(KeyboardUsage.LEFT_ARROW)
        RemoteControl.Right -> HidUsage.Key(KeyboardUsage.RIGHT_ARROW)
        RemoteControl.Select -> HidUsage.Key(KeyboardUsage.ENTER)
        RemoteControl.Menu -> HidUsage.Key(KeyboardUsage.ESCAPE)
        RemoteControl.Home -> HidUsage.Consumer(ConsumerUsage.DATA_ON_SCREEN)
        RemoteControl.PlayPause -> HidUsage.Consumer(ConsumerUsage.PLAY_PAUSE)
        RemoteControl.VolumeUp -> HidUsage.Consumer(ConsumerUsage.VOLUME_INCREMENT)
        RemoteControl.VolumeDown -> HidUsage.Consumer(ConsumerUsage.VOLUME_DECREMENT)
        RemoteControl.Mute -> HidUsage.Consumer(ConsumerUsage.MUTE)
    }
}
