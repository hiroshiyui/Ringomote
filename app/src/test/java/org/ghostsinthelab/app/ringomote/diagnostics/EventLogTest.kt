package org.ghostsinthelab.app.ringomote.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins down the ring-buffer behaviour of [EventLog]: order, the capacity limit and timestamps. */
class EventLogTest {

    /** Entries come back oldest first, each stamped with the clock at the time of recording. */
    @Test
    fun entriesKeepOrderAndTime() {
        var now = 1_000L
        val log = EventLog(capacity = 10, clock = { now })
        log.record("first")
        now = 2_000L
        log.record("second")
        assertEquals(
            listOf(EventLog.Entry(1_000L, "first"), EventLog.Entry(2_000L, "second")),
            log.entries(),
        )
    }

    /** When full, the oldest entry makes room for the newest. */
    @Test
    fun oldestEntryIsDroppedWhenFull() {
        val log = EventLog(capacity = 2, clock = { 0L })
        log.record("a")
        log.record("b")
        log.record("c")
        assertEquals(listOf("b", "c"), log.entries().map { it.message })
    }

    /** Clearing empties the log without affecting later records. */
    @Test
    fun clearForgetsEverything() {
        val log = EventLog(capacity = 2, clock = { 0L })
        log.record("a")
        log.clear()
        assertTrue(log.entries().isEmpty())
        log.record("b")
        assertEquals(listOf("b"), log.entries().map { it.message })
    }
}
