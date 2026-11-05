package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.history.ConnectionHistory
import org.ghostsinthelab.app.ringomote.hid.HidReports
import org.ghostsinthelab.app.ringomote.hid.KeyboardUsage
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.remote.RemoteSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests [RemoteViewModel]'s lifecycle rules with a [FakeTransport] and a real [RemoteSession],
 * on a virtual-time test dispatcher standing in for the app-wide serial dispatcher.
 *
 * Covers review items 4 (teardown runs off the main thread, in order with the next start) and
 * 6 (held controls are released when the link leaves Connected). Expected reports are built
 * with [HidReports], whose byte layout is pinned down by its own tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val tv = HostDevice("00:11:22:33:44:55", "TV")
    private val transport = FakeTransport()
    private val session = RemoteSession(transport)

    private val upDown = HidReports.keyboard(listOf(KeyboardUsage.UP_ARROW))
    private val allUp = HidReports.keyboard(emptyList())
    private val consumerUp = HidReports.consumer(0)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val history = FakeHistory()

    private fun newViewModel() = RemoteViewModel(transport, session, history, dispatcher, scope)

    /** Clears [vm] the way the framework does when its screen is finished. */
    private fun clear(vm: RemoteViewModel) = ViewModelStore().run {
        put("vm", vm)
        clear()
    }

    /** Item 6: a control held when the link drops is released, not left marked as held. */
    @Test
    fun leavingConnectedReleasesHeldControls() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        val vm = newViewModel()
        runCurrent()
        vm.press(RemoteControl.Up)
        runCurrent()
        assertEquals(setOf(RemoteControl.Up), session.held())

        transport.state.value = TransportState.Ready
        runCurrent()
        assertTrue(session.held().isEmpty())
    }

    /** Item 6: after a reconnect, pressing the button that was held before is sent again. */
    @Test
    fun pressAfterReconnectIsNotSwallowed() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        val vm = newViewModel()
        runCurrent()
        vm.press(RemoteControl.Up)
        runCurrent()
        transport.state.value = TransportState.Ready
        runCurrent()
        transport.state.value = TransportState.Connected(tv)
        runCurrent()
        transport.reports.clear()

        vm.press(RemoteControl.Up)
        runCurrent()
        assertEquals(listOf(upDown), transport.reports)
    }

    /** Item 6: staying connected does not trigger extra release reports. */
    @Test
    fun stayingConnectedSendsNoReleases() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        newViewModel()
        runCurrent()
        transport.reports.clear()
        transport.state.value = TransportState.Connected(tv.copy(name = "Renamed"))
        runCurrent()
        assertTrue(transport.reports.isEmpty())
    }

    /**
     * Item 4: clearing the ViewModel releases held keys but leaves the transport running:
     * the HID device must outlive the screen (RemoteService owns stopping it).
     */
    @Test
    fun clearingReleasesButKeepsTransport() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        val vm = newViewModel()
        runCurrent()
        vm.press(RemoteControl.Up)
        runCurrent()
        transport.calls.clear()
        transport.reports.clear()

        clear(vm)
        assertTrue("teardown must not run on the caller's thread", transport.calls.isEmpty())
        runCurrent()
        assertEquals(listOf(allUp, consumerUp), transport.reports)
        assertTrue(transport.calls.isEmpty())
    }

    /** Item 4: a release queued by a closing screen runs before a start queued by the next one. */
    @Test
    fun releaseFromOldScreenRunsBeforeStartFromNewOne() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        val old = newViewModel()
        runCurrent()
        old.press(RemoteControl.Up)
        runCurrent()
        transport.reports.clear()
        transport.calls.clear()
        clear(old)
        newViewModel().start()
        runCurrent()
        assertEquals(listOf(allUp, consumerUp), transport.reports)
        assertEquals(listOf("start", "bondedDevices"), transport.calls)
    }

    /** An accessibility long-click holds the control for LONG_PRESS_MILLIS, then releases it. */
    @Test
    fun holdReleasesAfterLongPressDuration() = scope.runTest {
        transport.state.value = TransportState.Connected(tv)
        val vm = newViewModel()
        runCurrent()
        transport.reports.clear()

        vm.hold(RemoteControl.Up)
        runCurrent()
        assertEquals(listOf(upDown), transport.reports)
        advanceTimeBy(RemoteViewModel.LONG_PRESS_MILLIS - 1)
        assertEquals(1, transport.reports.size)
        advanceTimeBy(2)
        assertEquals(listOf(upDown, allUp), transport.reports)
    }

    /** The first Connected state is recorded, so later launches start with details collapsed. */
    @Test
    fun connectingMarksHistory() = scope.runTest {
        val vm = newViewModel()
        runCurrent()
        assertFalse(vm.hasConnectedBefore.value)
        transport.state.value = TransportState.Ready
        runCurrent()
        assertFalse(vm.hasConnectedBefore.value)

        transport.state.value = TransportState.Connected(tv)
        runCurrent()
        assertTrue(vm.hasConnectedBefore.value)
        transport.state.value = TransportState.Ready
        runCurrent()
        assertTrue("history never resets", vm.hasConnectedBefore.value)
    }

    /** In-memory [ConnectionHistory]. */
    private class FakeHistory : ConnectionHistory {
        override val hasConnectedBefore = MutableStateFlow(false)
        override fun markConnected() { hasConnectedBefore.value = true }
    }

    /** Records lifecycle calls and reports; reports succeed only while Connected. */
    private class FakeTransport : HidTransport {
        override val state = MutableStateFlow<TransportState>(TransportState.Stopped)
        val calls = mutableListOf<String>()
        val reports = mutableListOf<HidReport>()

        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun bondedDevices(): List<HostDevice> { calls += "bondedDevices"; return emptyList() }
        override fun connect(address: String): Boolean { calls += "connect"; return true }
        override fun disconnect(): Boolean { calls += "disconnect"; return true }
        override fun send(report: HidReport): Boolean {
            reports += report
            return state.value is TransportState.Connected
        }
    }
}
