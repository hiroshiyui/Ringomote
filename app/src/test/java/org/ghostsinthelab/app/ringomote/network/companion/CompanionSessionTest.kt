package org.ghostsinthelab.app.ringomote.network.companion

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [CompanionSession]'s link-keeping rules on virtual time with a scripted
 * [CompanionLinkPort]: when it connects, how it backs off, when it gives up, how long an
 * unwanted link lingers, and that held buttons are lifted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionSessionTest {

    private class FakeLink(override val name: String) : CompanionLink {
        val sent = mutableListOf<String>()
        var closed = false
        var failNext = false
        override fun send(command: CompanionCommand, down: Boolean) {
            if (failNext) { failNext = false; throw IOException("gone") }
            sent += "${if (down) "down" else "up"} $command"
        }
        override fun close() { closed = true }
    }

    private class FakePort : CompanionLinkPort {
        /** Outcomes in order; a [Throwable] is thrown, anything else opens a link. */
        val script = ArrayDeque<Any>()
        val links = mutableListOf<FakeLink>()
        var opens = 0
        override fun open(name: String): CompanionLink {
            opens++
            val next = script.removeFirstOrNull() ?: Unit
            if (next is Throwable) throw next
            return FakeLink(name).also { links += it }
        }
    }

    private val port = FakePort()

    private fun runSession(block: suspend kotlinx.coroutines.test.TestScope.(CompanionSession) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = CompanionSession(port, backgroundScope, dispatcher)
        block(session)
    }

    /** Target plus wanted is what opens the link; either alone does nothing. */
    @Test
    fun connectsWhenTargetedAndWanted() = runSession { session ->
        session.target("TV")
        runCurrent()
        assertEquals(0, port.opens)
        assertEquals(CompanionState.Off, session.state.value)
        session.setWanted(true)
        runCurrent()
        assertEquals(1, port.opens)
        assertEquals(CompanionState.Connected("TV"), session.state.value)
    }

    /** Unreachable: back off 1 s, 2 s, ... and keep trying while wanted. */
    @Test
    fun backsOffWhileUnreachable() = runSession { session ->
        port.script += IOException("down")
        port.script += IOException("down")
        session.target("TV"); session.setWanted(true)
        runCurrent()
        assertEquals(CompanionState.Unreachable("TV"), session.state.value)
        assertEquals(1, port.opens)
        advanceTimeBy(CompanionSession.BACKOFF_MILLIS[0] + 1)
        assertEquals(2, port.opens)
        advanceTimeBy(CompanionSession.BACKOFF_MILLIS[1] + 1)
        assertEquals(3, port.opens)
        assertEquals(CompanionState.Connected("TV"), session.state.value)
    }

    /** A refused pairing stops the retries until retry() is called. */
    @Test
    fun rejectedWaitsForRetry() = runSession { session ->
        port.script += PairingException("no")
        session.target("TV"); session.setWanted(true)
        runCurrent()
        assertEquals(CompanionState.Rejected("TV"), session.state.value)
        advanceTimeBy(60_000)
        assertEquals(1, port.opens)
        session.retry()
        runCurrent()
        assertEquals(2, port.opens)
        assertEquals(CompanionState.Connected("TV"), session.state.value)
    }

    /** An unwanted link survives the grace period, then closes; wanting it again in time keeps it. */
    @Test
    fun unwantedLinkClosesAfterGrace() = runSession { session ->
        session.target("TV"); session.setWanted(true)
        runCurrent()
        session.setWanted(false)
        advanceTimeBy(CompanionSession.GRACE_MILLIS - 1)
        assertFalse(port.links[0].closed)
        session.setWanted(true)
        runCurrent()
        assertEquals(1, port.opens)
        assertEquals(CompanionState.Connected("TV"), session.state.value)

        session.setWanted(false)
        advanceTimeBy(CompanionSession.GRACE_MILLIS + 1)
        assertTrue(port.links[0].closed)
        assertEquals(CompanionState.Off, session.state.value)
    }

    /** Changing the target closes the old link at once and opens the new one. */
    @Test
    fun targetChangeSwitchesLinks() = runSession { session ->
        session.target("A"); session.setWanted(true)
        runCurrent()
        session.target("B")
        runCurrent()
        assertTrue(port.links[0].closed)
        assertEquals("B", port.links[1].name)
        assertEquals(CompanionState.Connected("B"), session.state.value)
    }

    /** Presses go out as down/up; releaseAll lifts what is still held, once. */
    @Test
    fun pressReleaseAndReleaseAll() = runSession { session ->
        session.target("TV"); session.setWanted(true)
        runCurrent()
        assertTrue(session.press(RemoteControl.Up))
        assertTrue(session.press(RemoteControl.Select))
        assertTrue(session.release(RemoteControl.Up))
        session.releaseAll()
        session.releaseAll()
        assertEquals(listOf("down Up", "down Select", "up Up", "up Select"), port.links[0].sent)
    }

    /** A failed send drops the link, reports false, and a fresh link is opened by itself. */
    @Test
    fun failedSendReconnects() = runSession { session ->
        session.target("TV"); session.setWanted(true)
        runCurrent()
        port.links[0].failNext = true
        assertFalse(session.press(RemoteControl.Up))
        assertTrue(port.links[0].closed)
        runCurrent()
        assertEquals(2, port.opens)
        assertEquals(CompanionState.Connected("TV"), session.state.value)
        assertTrue(session.press(RemoteControl.Up))
        assertEquals(listOf("down Up"), port.links[1].sent)
    }
}
