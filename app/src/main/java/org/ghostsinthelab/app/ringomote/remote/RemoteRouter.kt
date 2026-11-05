package org.ghostsinthelab.app.ringomote.remote

/**
 * A second way to deliver a [RemoteControl], besides the HID reports of [RemoteSession]: the
 * Wi-Fi remote session ([org.ghostsinthelab.app.ringomote.network.companion.CompanionSession]).
 * Kept as an interface here so `remote/` stays free of network code.
 */
interface CompanionInput {
    /** Whether this link can carry [control] at all. */
    fun supports(control: RemoteControl): Boolean
    suspend fun press(control: RemoteControl): Boolean
    suspend fun release(control: RemoteControl): Boolean
    suspend fun releaseAll()
}

/**
 * Sends each press over the link that is live, Wi-Fi first: while the Wi-Fi session is open
 * ([companionConnected]) a control goes to [companion], unless it lacks the control (Mute),
 * which falls through to Bluetooth; otherwise, while the Bluetooth host is subscribed
 * ([bluetoothConnected]), it goes to [session] as a HID report; otherwise it is dropped (the
 * remote is disabled then anyway). Wi-Fi comes first because the Companion session is the one
 * the phone owns: it reconnects by itself and is not subject to the Apple TV's cached picture
 * of the HID keyboard, which can leave a subscribed Bluetooth link that swallows every key.
 *
 * A control is released on the link it was pressed on, remembered in [heldOn], so a link
 * change between finger-down and finger-up never leaves a key stuck on the Apple TV.
 * [releaseAll] lifts everything on both links.
 *
 * Not thread-safe by itself: the ViewModel calls it on the app's serial dispatcher only.
 */
class RemoteRouter(
    private val session: RemoteSession,
    private val companion: CompanionInput,
    private val bluetoothConnected: () -> Boolean,
    private val companionConnected: () -> Boolean,
) {
    /** The link a new press would take right now. */
    enum class Link { Bluetooth, WiFi }

    private val heldOn = mutableMapOf<RemoteControl, Link>()

    /** Which link is live, Wi-Fi first; null when neither can carry input. */
    fun activeLink(): Link? = when {
        companionConnected() -> Link.WiFi
        bluetoothConnected() -> Link.Bluetooth
        else -> null
    }

    /** The link [control] would take: the active one, or Bluetooth for a control Wi-Fi lacks. */
    private fun linkFor(control: RemoteControl): Link? = when (activeLink()) {
        Link.WiFi -> if (companion.supports(control)) Link.WiFi else Link.Bluetooth.takeIf { bluetoothConnected() }
        Link.Bluetooth -> Link.Bluetooth
        null -> null
    }

    /** Whether [control] can be sent right now; only Wi-Fi lacks some (Mute). */
    fun supports(control: RemoteControl): Boolean = linkFor(control) != null

    suspend fun press(control: RemoteControl): Boolean {
        if (control in heldOn) return true
        return when (val link = linkFor(control)) {
            Link.Bluetooth -> session.press(control).also { heldOn[control] = link }
            Link.WiFi -> companion.press(control).also { heldOn[control] = link }
            null -> false
        }
    }

    suspend fun release(control: RemoteControl): Boolean = when (heldOn.remove(control)) {
        Link.Bluetooth -> session.release(control)
        Link.WiFi -> companion.release(control)
        null -> true
    }

    suspend fun tap(control: RemoteControl): Boolean {
        val pressed = press(control)
        val released = release(control)
        return pressed && released
    }

    /** Lifts every held control on both links; call whenever input stops or the link changes. */
    suspend fun releaseAll() {
        heldOn.clear()
        session.releaseAll()
        companion.releaseAll()
    }
}
