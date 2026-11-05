package org.ghostsinthelab.app.ringomote.diagnostics

/**
 * A bounded, in-memory log of what the Bluetooth transport saw: server opened, services
 * added, advertising started or refused, hosts connecting and leaving with the HCI reason,
 * subscriptions, failed notifications. The diagnostics report in Settings > Advanced prints
 * it so a user can hand a developer the sequence that led to a problem, which `adb logcat`
 * would otherwise be the only way to see.
 *
 * Keeps the newest [capacity] entries; older ones are dropped. Pure Kotlin and thread-safe,
 * because the transport records from binder threads and the UI reads from the main thread.
 * [clock] is injectable so tests get stable timestamps. [mirror] receives every message as
 * well; the app passes a logcat writer so `adb logcat -s Ringomote` shows the same sequence
 * live, which is how link problems get debugged with the phone on a cable.
 */
class EventLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mirror: (String) -> Unit = {},
) {
    /** One recorded event, with the wall-clock time it was recorded at. */
    data class Entry(val timeMillis: Long, val message: String)

    private val entries = ArrayDeque<Entry>(capacity)

    /** Appends [message], dropping the oldest entry when full. */
    @Synchronized
    fun record(message: String) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(Entry(clock(), message))
        mirror(message)
    }

    /** The entries recorded so far, oldest first. */
    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    /** Forgets everything; the next entry starts a fresh log. */
    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /** Enough for a long session; each entry is a short line. */
        const val DEFAULT_CAPACITY = 500
    }
}
