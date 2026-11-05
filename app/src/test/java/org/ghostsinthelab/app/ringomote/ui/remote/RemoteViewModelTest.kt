package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.lifecycle.ViewModelStore
import java.io.IOException
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
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCommand
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentials
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.CompanionLink
import org.ghostsinthelab.app.ringomote.network.companion.CompanionLinkPort
import org.ghostsinthelab.app.ringomote.network.companion.CompanionSession
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.network.companion.PairingException
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests [RemoteViewModel]'s lifecycle rules with a real [CompanionSession] over a fake
 * [CompanionLinkPort], on a virtual-time test dispatcher standing in for the app-wide serial
 * dispatcher. Expected values are the `_hidC` down/up pairs the session sends per control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val port = FakePort()
    private val networkSettings = FakeNetworkSettings()
    private val credentials = FakeCredentialsStore()
    private val session = CompanionSession(port, scope.backgroundScope, dispatcher)

    private fun newViewModel() =
        RemoteViewModel(session, networkSettings, credentials, dispatcher, scope)

    /** A ViewModel whose remote is shown in the foreground, with "TV" paired and connected. */
    private fun connectedViewModel(): RemoteViewModel {
        credentials.credentials.value = mapOf("TV" to CompanionCredentials(ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(32)))
        val vm = newViewModel()
        vm.setRemoteShown(true)
        scope.runCurrent()
        assertEquals(CompanionState.Connected("TV"), session.state.value)
        return vm
    }

    /** Clears [vm] the way the framework does when its screen is finished. */
    private fun clear(vm: RemoteViewModel) = ViewModelStore().run {
        put("vm", vm)
        clear()
    }

    /** The only pairing becomes the target, and the session connects while the remote is shown. */
    @Test
    fun onlyPairingIsTargetedWhileShown() = scope.runTest {
        newViewModel()
        runCurrent()
        assertEquals("no pairing, nothing to target", CompanionState.Off, session.state.value)
        val vm = connectedViewModel()
        vm.setRemoteShown(false)
        advanceTimeBy(CompanionSession.GRACE_MILLIS + 1)
        runCurrent()
        assertEquals(CompanionState.Off, session.state.value)
    }

    /** A control held when the link drops is released, not left marked as held. */
    @Test
    fun leavingConnectedReleasesHeldControls() = scope.runTest {
        val vm = connectedViewModel()
        vm.press(RemoteControl.Up)
        runCurrent()
        assertEquals(listOf("down Up"), port.links[0].sent)

        port.links[0].failNext = true
        vm.press(RemoteControl.Select)
        runCurrent()
        assertEquals(2, port.links.size)
        vm.press(RemoteControl.Up)
        runCurrent()
        assertEquals(listOf("down Up"), port.links[1].sent)
    }

    /** Clearing the ViewModel releases held keys off the caller's thread and lets the session go. */
    @Test
    fun clearingReleasesAndStopsWanting() = scope.runTest {
        val vm = connectedViewModel()
        vm.press(RemoteControl.Up)
        runCurrent()

        clear(vm)
        assertEquals("teardown must not run on the caller's thread", listOf("down Up"), port.links[0].sent)
        runCurrent()
        assertEquals(listOf("down Up", "up Up"), port.links[0].sent)
        advanceTimeBy(CompanionSession.GRACE_MILLIS + 1)
        runCurrent()
        assertTrue(port.links[0].closed)
    }

    /** An accessibility long-click holds the control for LONG_PRESS_MILLIS, then releases it. */
    @Test
    fun holdReleasesAfterLongPressDuration() = scope.runTest {
        val vm = connectedViewModel()
        vm.hold(RemoteControl.Home)
        runCurrent()
        assertEquals(listOf("down Home"), port.links[0].sent)
        advanceTimeBy(RemoteViewModel.LONG_PRESS_MILLIS - 1)
        assertEquals(1, port.links[0].sent.size)
        advanceTimeBy(2)
        assertEquals(listOf("down Home", "up Home"), port.links[0].sent)
    }

    /** A pairing the Apple TV refuses is dropped from the store, so the UI offers Pair again. */
    @Test
    fun rejectedPairingIsForgotten() = scope.runTest {
        credentials.credentials.value = mapOf("TV" to CompanionCredentials(ByteArray(32), ByteArray(32), ByteArray(32), ByteArray(32)))
        port.rejectNext = true
        val vm = newViewModel()
        vm.setRemoteShown(true)
        runCurrent()
        assertTrue(credentials.credentials.value.isEmpty())
        assertEquals(CompanionState.Off, session.state.value)
    }

    /** A tap is one down/up pair. */
    @Test
    fun tapSendsDownAndUp() = scope.runTest {
        val vm = connectedViewModel()
        vm.tap(RemoteControl.Right)
        runCurrent()
        assertEquals(listOf("down Right", "up Right"), port.links[0].sent)
    }

    private class FakeNetworkSettings : NetworkSettings {
        override val selectedAppleTv = MutableStateFlow<String?>(null)
        override fun setSelectedAppleTv(name: String?) { selectedAppleTv.value = name }
        override val remoteName = MutableStateFlow<String?>(null)
        override fun setRemoteName(name: String?) { remoteName.value = name }
    }

    private class FakeCredentialsStore : CompanionCredentialsStore {
        override val credentials = MutableStateFlow<Map<String, CompanionCredentials>>(emptyMap())
        override fun put(appleTvId: String, credentials: CompanionCredentials) = Unit
        override fun remove(appleTvId: String) { credentials.value = credentials.value - appleTvId }
        override val deviceId = "AA:BB:CC:DD:EE:FF"
        override fun endpoint(appleTvId: String): CompanionCredentialsStore.Endpoint? = null
        override fun putEndpoint(appleTvId: String, endpoint: CompanionCredentialsStore.Endpoint) = Unit
    }

    /** Opens a recording link per call; a link can be told to fail its next send. */
    private class FakePort : CompanionLinkPort {
        val links = mutableListOf<FakeLink>()
        var rejectNext = false
        override fun open(name: String): CompanionLink {
            if (rejectNext) { rejectNext = false; throw PairingException("forgotten") }
            return FakeLink(name).also { links += it }
        }
    }

    private class FakeLink(override val name: String) : CompanionLink {
        val sent = mutableListOf<String>()
        var failNext = false
        var closed = false
        override fun send(command: CompanionCommand, down: Boolean) {
            if (failNext) { failNext = false; throw IOException("gone") }
            sent += "${if (down) "down" else "up"} $command"
        }
        override fun close() { closed = true }
    }
}
