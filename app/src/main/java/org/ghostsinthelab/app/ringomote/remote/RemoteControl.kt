package org.ghostsinthelab.app.ringomote.remote

/**
 * The controls of the Siri Remote (1st generation) that Ringomote reproduces.
 *
 * Swipes on the touch surface become [Up], [Down], [Left] and [Right]; clicking it is
 * [Select]. Each control is sent to the Apple TV as a Companion Link button
 * ([org.ghostsinthelab.app.ringomote.network.companion.CompanionKeyMap]). Siri voice input
 * is not reproduced, and Mute has no Companion Link button.
 */
enum class RemoteControl {
    Up, Down, Left, Right, Select, Menu, Home, PlayPause, VolumeUp, VolumeDown,
}
