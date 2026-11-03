package org.ghostsinthelab.app.ringomote.remote

import org.ghostsinthelab.app.ringomote.hid.ConsumerUsage
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportSink
import org.ghostsinthelab.app.ringomote.hid.HidReports
import org.ghostsinthelab.app.ringomote.hid.HidUsage
import org.ghostsinthelab.app.ringomote.hid.KeyboardUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [RemoteSession]: which reports a sequence of presses and releases produces, and the
 * held-state bookkeeping behind it.
 *
 * A [RecordingSink] stands in for the Bluetooth transport. Expected reports are built with
 * [HidReports] (whose byte layout [org.ghostsinthelab.app.ringomote.hid.HidReportsTest] pins
 * down) from usages in the HID Usage Tables. The central property is the stuck-key invariant:
 * HID input is state-based, so after every press is released the host's last keyboard and
 * consumer reports must both say "nothing pressed".
 */
class RemoteSessionTest {

    /** Records every report sent; returns [result] so send failures can be simulated. */
    private class RecordingSink(var result: Boolean = true) : HidReportSink {
        val sent = mutableListOf<HidReport>()
        override fun send(report: HidReport): Boolean {
            sent += report
            return result
        }
    }

    private val sink = RecordingSink()
    private val session = RemoteSession(sink)

    private fun kb(vararg keys: Int) = HidReports.keyboard(keys.toList())
    private fun consumer(usage: Int) = HidReports.consumer(usage)

    /** The last report the host received for [id], or null if none was sent. */
    private fun lastFor(id: Int) = sink.sent.lastOrNull { it.id == id }

    /** Pressing a keyboard control sends one keyboard report holding its usage. */
    @Test
    fun pressSendsPressReport() {
        assertTrue(session.press(RemoteControl.Up))
        assertEquals(listOf(kb(KeyboardUsage.UP_ARROW)), sink.sent)
        assertEquals(setOf(RemoteControl.Up), session.held())
    }

    /** Releasing a held keyboard control sends the empty keyboard report. */
    @Test
    fun releaseSendsReleaseReport() {
        session.press(RemoteControl.Select)
        assertTrue(session.release(RemoteControl.Select))
        assertEquals(listOf(kb(KeyboardUsage.ENTER), kb()), sink.sent)
        assertTrue(session.held().isEmpty())
    }

    /** Pressing and releasing a consumer control sends its usage then 0 on the consumer report. */
    @Test
    fun consumerPressAndRelease() {
        session.press(RemoteControl.PlayPause)
        session.release(RemoteControl.PlayPause)
        assertEquals(listOf(consumer(ConsumerUsage.PLAY_PAUSE), consumer(0)), sink.sent)
    }

    /** Stuck-key invariant: for every control, press then release leaves both reports all-released. */
    @Test
    fun everyPressEndsReleased() {
        for (control in RemoteControl.entries) {
            val s = RecordingSink()
            val session = RemoteSession(s)
            session.press(control)
            session.release(control)
            val last = s.sent.last()
            assertEquals("Last report after $control", HidReports.idle(last.id), last)
            assertTrue(session.held().isEmpty())
        }
    }

    /** Stuck-key invariant over an interleaved sequence: once everything is released, the host's last reports are idle. */
    @Test
    fun interleavedPressesEndReleased() {
        session.press(RemoteControl.Up)
        session.press(RemoteControl.VolumeUp)
        session.press(RemoteControl.Left)
        session.press(RemoteControl.Home) // replaces VolumeUp
        session.release(RemoteControl.Up)
        session.release(RemoteControl.VolumeUp) // no longer held: no report
        session.release(RemoteControl.Home)
        session.release(RemoteControl.Left)
        assertEquals(kb(), lastFor(1))
        assertEquals(consumer(0), lastFor(2))
        assertTrue(session.held().isEmpty())
    }

    /** Two arrows held together share one keyboard report; releasing one keeps the other down. */
    @Test
    fun twoArrowsHeldTogether() {
        session.press(RemoteControl.Up)
        session.press(RemoteControl.Right)
        session.release(RemoteControl.Up)
        assertEquals(
            listOf(
                kb(KeyboardUsage.UP_ARROW),
                kb(KeyboardUsage.UP_ARROW, KeyboardUsage.RIGHT_ARROW),
                kb(KeyboardUsage.RIGHT_ARROW),
            ),
            sink.sent,
        )
        assertEquals(setOf(RemoteControl.Right), session.held())
    }

    /** Pressing an already-held control sends nothing more and reports success. */
    @Test
    fun doublePressIsIdempotent() {
        session.press(RemoteControl.Down)
        assertTrue(session.press(RemoteControl.Down))
        assertEquals(listOf(kb(KeyboardUsage.DOWN_ARROW)), sink.sent)
        session.press(RemoteControl.VolumeDown)
        assertTrue(session.press(RemoteControl.VolumeDown))
        assertEquals(2, sink.sent.size)
    }

    /** Releasing a control that is not held sends nothing and reports success. */
    @Test
    fun releaseOfUnheldControlSendsNothing() {
        assertTrue(session.release(RemoteControl.Menu))
        assertTrue(session.release(RemoteControl.Home))
        assertTrue(sink.sent.isEmpty())
    }

    /** tap() sends exactly one press report followed by one release report. */
    @Test
    fun tapSendsPressThenRelease() {
        assertTrue(session.tap(RemoteControl.Menu))
        assertEquals(listOf(kb(KeyboardUsage.ESCAPE), kb()), sink.sent)
        sink.sent.clear()
        assertTrue(session.tap(RemoteControl.Home))
        assertEquals(listOf(consumer(ConsumerUsage.AC_HOME), consumer(0)), sink.sent)
        assertTrue(session.held().isEmpty())
    }

    /** The consumer report holds one usage: a second consumer press replaces the first in report and held(). */
    @Test
    fun consumerPressReplacesPrevious() {
        session.press(RemoteControl.VolumeUp)
        session.press(RemoteControl.PlayPause)
        assertEquals(
            listOf(consumer(ConsumerUsage.VOLUME_INCREMENT), consumer(ConsumerUsage.PLAY_PAUSE)),
            sink.sent,
        )
        assertEquals(setOf(RemoteControl.PlayPause), session.held())
        // The replaced control is no longer held, so releasing it must not cancel PlayPause.
        session.release(RemoteControl.VolumeUp)
        assertEquals(2, sink.sent.size)
    }

    /** releaseAll() sends both idle reports and clears held(), even when keys of both kinds are down. */
    @Test
    fun releaseAllSendsBothZeroReportsAndClears() {
        session.press(RemoteControl.Up)
        session.press(RemoteControl.Home)
        sink.sent.clear()
        session.releaseAll()
        assertEquals(listOf(kb(), consumer(0)), sink.sent)
        assertTrue(session.held().isEmpty())
    }

    /** releaseAll() sends both idle reports even when nothing is held, to reset the host after an interruption. */
    @Test
    fun releaseAllWhenIdleStillSends() {
        session.releaseAll()
        assertEquals(listOf(kb(), consumer(0)), sink.sent)
    }

    /** After releaseAll(), pressing a previously held key works again (state really was cleared). */
    @Test
    fun pressAfterReleaseAllWorks() {
        session.press(RemoteControl.Up)
        session.releaseAll()
        sink.sent.clear()
        session.press(RemoteControl.Up)
        assertEquals(listOf(kb(KeyboardUsage.UP_ARROW)), sink.sent)
    }

    /** With six keys held, a seventh is refused: false, no report, not added to held(). */
    @Test
    fun seventhSimultaneousKeyIsRefused() {
        // Seven controls mapped to seven distinct Keyboard usages (a..g = 0x04..0x0A).
        val controls = RemoteControl.entries.take(7)
        val keyMap = { c: RemoteControl -> HidUsage.Key(0x04 + c.ordinal) }
        val s = RecordingSink()
        val session = RemoteSession(s, keyMap)
        controls.take(6).forEach { assertTrue(session.press(it)) }
        assertEquals(kb(0x04, 0x05, 0x06, 0x07, 0x08, 0x09), s.sent.last())
        val sentBefore = s.sent.size

        assertFalse(session.press(controls[6]))
        assertEquals(sentBefore, s.sent.size)
        assertEquals(controls.take(6).toSet(), session.held())

        // Freeing a slot lets the seventh key in.
        session.release(controls[0])
        assertTrue(session.press(controls[6]))
        assertEquals(kb(0x05, 0x06, 0x07, 0x08, 0x09, 0x0A), s.sent.last())
    }

    /** A sink that fails to send makes press, release and tap return false. */
    @Test
    fun sinkFailurePropagates() {
        sink.result = false
        assertFalse(session.press(RemoteControl.Up))
        assertFalse(session.release(RemoteControl.Up))
        assertFalse(session.press(RemoteControl.PlayPause))
        assertFalse(session.release(RemoteControl.PlayPause))
        assertFalse(session.tap(RemoteControl.Select))
    }
}
