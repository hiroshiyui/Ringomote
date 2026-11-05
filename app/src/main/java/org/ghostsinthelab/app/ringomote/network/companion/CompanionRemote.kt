package org.ghostsinthelab.app.ringomote.network.companion

import org.ghostsinthelab.app.ringomote.remote.RemoteControl

/**
 * The buttons a Companion Link `_hidC` message can press, with the codes tvOS expects
 * (`_hidC` value; `_hBtS` is 1 for down and 2 for up). The table is pyatv's `HidCommand`
 * and matches what the iOS Remote sends; [Wake] is the one that turns a sleeping Apple TV on.
 */
enum class CompanionCommand(val code: Int) {
    Up(1), Down(2), Left(3), Right(4), Menu(5), Select(6), Home(7),
    VolumeUp(8), VolumeDown(9), Siri(10), Screensaver(11), Sleep(12), Wake(13),
    PlayPause(14), ChannelUp(15), ChannelDown(16), Guide(17), PageUp(18), PageDown(19),
}

/**
 * Maps each [RemoteControl] to its [CompanionCommand], the Wi-Fi counterpart of
 * [org.ghostsinthelab.app.ringomote.remote.HidKeyMap].
 *
 * [RemoteControl.Mute] has no counterpart: Companion Link's HID table has no mute button and
 * its media-control message (`_mcc`) only sets an absolute volume, so Mute is unavailable over
 * Wi-Fi and the remote shows it disabled then. Home over Companion is the TV button: a tap
 * goes Home, a hold opens Control Center, like the Bluetooth usage.
 */
object CompanionKeyMap {
    fun commandFor(control: RemoteControl): CompanionCommand? = when (control) {
        RemoteControl.Up -> CompanionCommand.Up
        RemoteControl.Down -> CompanionCommand.Down
        RemoteControl.Left -> CompanionCommand.Left
        RemoteControl.Right -> CompanionCommand.Right
        RemoteControl.Select -> CompanionCommand.Select
        RemoteControl.Menu -> CompanionCommand.Menu
        RemoteControl.Home -> CompanionCommand.Home
        RemoteControl.PlayPause -> CompanionCommand.PlayPause
        RemoteControl.VolumeUp -> CompanionCommand.VolumeUp
        RemoteControl.VolumeDown -> CompanionCommand.VolumeDown
        RemoteControl.Mute -> null
    }

    /** Whether [control] can be sent over Companion Link at all. */
    fun supports(control: RemoteControl): Boolean = commandFor(control) != null
}
