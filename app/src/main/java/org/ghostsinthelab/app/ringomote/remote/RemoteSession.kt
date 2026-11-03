package org.ghostsinthelab.app.ringomote.remote

import org.ghostsinthelab.app.ringomote.hid.HidReportDescriptor
import org.ghostsinthelab.app.ringomote.hid.HidReportSink
import org.ghostsinthelab.app.ringomote.hid.HidReports
import org.ghostsinthelab.app.ringomote.hid.HidUsage

/**
 * Turns presses and releases of [RemoteControl]s into HID reports and tracks what is held.
 *
 * HID input is state-based: the host treats a key as down until a report without it arrives.
 * So every [press] must be matched by a [release], and [releaseAll] must run whenever input
 * stops unexpectedly (the app goes to the background, the host disconnects); otherwise the
 * Apple TV sees a stuck key.
 *
 * Keyboard usages can be held together (up to [HidReportDescriptor.KEYBOARD_MAX_KEYS]); the
 * consumer report holds one usage, so pressing a second consumer control replaces the first.
 *
 * A control stays marked as held even if sending its press report failed: the host's state is
 * unknown then, so the later release (or [releaseAll]) is still sent to reset it.
 *
 * Usages in [keyMap] must be distinct per control; held keys are tracked by usage ID.
 *
 * Thread-safe: all methods are synchronized, and reports are sent in call order. Sending is a
 * binder call, so call these off the main thread.
 *
 * @param sink where the reports go.
 * @param keyMap the HID usage for each control; replaceable in tests.
 */
class RemoteSession(
    private val sink: HidReportSink,
    private val keyMap: (RemoteControl) -> HidUsage = HidKeyMap::usageFor,
) {
    private val heldControls = mutableSetOf<RemoteControl>()
    private val heldKeys = LinkedHashSet<Int>()
    private var heldConsumer: RemoteControl? = null

    /**
     * Presses [control] and keeps it held. Pressing a control that is already held does
     * nothing. Returns false if the report could not be sent or all key slots are taken.
     */
    @Synchronized
    fun press(control: RemoteControl): Boolean {
        if (control in heldControls) return true
        return when (val usage = keyMap(control)) {
            is HidUsage.Key -> {
                if (heldKeys.size >= HidReportDescriptor.KEYBOARD_MAX_KEYS) return false
                heldControls += control
                heldKeys += usage.id
                sink.send(HidReports.keyboard(heldKeys))
            }

            is HidUsage.Consumer -> {
                heldConsumer?.let { heldControls -= it }
                heldConsumer = control
                heldControls += control
                sink.send(HidReports.consumer(usage.id))
            }
        }
    }

    /** Releases [control]; releasing a control that is not held does nothing. */
    @Synchronized
    fun release(control: RemoteControl): Boolean {
        if (!heldControls.remove(control)) return true
        return when (val usage = keyMap(control)) {
            is HidUsage.Key -> {
                heldKeys -= usage.id
                sink.send(HidReports.keyboard(heldKeys))
            }

            is HidUsage.Consumer -> {
                heldConsumer = null
                sink.send(HidReports.consumer(0))
            }
        }
    }

    /** Presses and immediately releases [control]: a single click. */
    @Synchronized
    fun tap(control: RemoteControl): Boolean {
        val pressed = press(control)
        val released = release(control)
        return pressed && released
    }

    /**
     * Releases everything, sending both "nothing pressed" reports even if nothing seems held,
     * so the host's view is reset after an interruption.
     */
    @Synchronized
    fun releaseAll() {
        heldControls.clear()
        heldKeys.clear()
        heldConsumer = null
        sink.send(HidReports.keyboard(emptyList()))
        sink.send(HidReports.consumer(0))
    }

    /** Controls currently held, for tests and diagnostics. */
    @Synchronized
    fun held(): Set<RemoteControl> = heldControls.toSet()
}
