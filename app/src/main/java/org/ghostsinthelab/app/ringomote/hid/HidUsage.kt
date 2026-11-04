package org.ghostsinthelab.app.ringomote.hid

/**
 * One HID usage the remote can send, tagged with the usage page it belongs to.
 *
 * The page decides which report carries the usage: [Key] goes into the keyboard report
 * ([HidReportDescriptor.KEYBOARD_REPORT_ID]) and [Consumer] into the consumer-control report
 * ([HidReportDescriptor.CONSUMER_REPORT_ID]). The `init` checks keep every usage inside the
 * logical range the descriptor declares, so a host never receives a value it was told cannot
 * occur.
 */
sealed interface HidUsage {

    /** A usage ID on the Keyboard/Keypad page (0x07); see [KeyboardUsage]. */
    data class Key(val id: Int) : HidUsage {
        init {
            require(id in 1..HidReportDescriptor.KEYBOARD_MAX_USAGE) { "Keyboard usage out of range: $id" }
        }
    }

    /** A usage ID on the Consumer page (0x0C); see [ConsumerUsage]. */
    data class Consumer(val id: Int) : HidUsage {
        init {
            require(id in 1..HidReportDescriptor.CONSUMER_MAX_USAGE) { "Consumer usage out of range: $id" }
        }
    }
}

/** Keyboard/Keypad page (0x07) usage IDs, from the USB HID Usage Tables, section 10. */
object KeyboardUsage {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val RIGHT_ARROW = 0x4F
    const val LEFT_ARROW = 0x50
    const val DOWN_ARROW = 0x51
    const val UP_ARROW = 0x52
}

/** Consumer page (0x0C) usage IDs, from the USB HID Usage Tables, section 15. */
object ConsumerUsage {
    const val MENU = 0x0040
    const val PLAY_PAUSE = 0x00CD
    const val VOLUME_INCREMENT = 0x00E9
    const val VOLUME_DECREMENT = 0x00EA

    /** Mute: the usage the 2nd-generation Siri Remote sends for its Mute button. */
    const val MUTE = 0x00E2
    const val AC_HOME = 0x0223

    /**
     * "Data On Screen": the usage the 2nd-generation Siri Remote sends for its TV button.
     * tvOS applies its own press-and-hold rule to it: a tap goes Home, a hold (about 1.5 s)
     * opens Control Center. Verified on a real Apple TV on 2026-11-03.
     */
    const val DATA_ON_SCREEN = 0x0060
}
