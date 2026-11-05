package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.history.ConnectionHistory
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.CompanionSession
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.remote.RemoteRouter

/**
 * State and actions for the remote screen.
 *
 * Everything that talks to the transport runs on [serialDispatcher], shared app-wide (see
 * [org.ghostsinthelab.app.ringomote.AppContainer.serialDispatcher]): reports reach the host in
 * the order the user produced them, binder calls stay off the main thread, and start/stop
 * requests from successive screens cannot overtake each other.
 *
 * Input goes through [RemoteRouter]: over Bluetooth while the Apple TV is subscribed, else over
 * the Wi-Fi [CompanionSession] while it is connected ([activeLink]). Whenever the active link
 * changes, held controls are released on both links, so a finger that was down when the Apple
 * TV disconnected does not block the same button after reconnecting.
 *
 * The Wi-Fi session is wanted only while the remote is on screen ([setRemoteShown]) in the
 * foreground ([setForeground]) and Bluetooth is not connected; its target is the paired Apple
 * TV the Remote tab is aimed at ([NetworkSettings.selectedAppleTv], or the only pairing),
 * and only with the network switch on.
 *
 * The first successful connection is recorded in [connectionHistory]; until then
 * [hasConnectedBefore] is false and the UI opens the pairing details by default.
 *
 * The ViewModel starts the transport on request ([start], after permissions are granted) but
 * never stops it: the HID device must outlive the screen, or the Apple TV drops the keyboard
 * (see [org.ghostsinthelab.app.ringomote.service.RemoteService], which keeps the process and
 * the transport alive and is the only place that stops it). [onCleared] only releases held
 * keys, in [applicationScope] because [viewModelScope] is already cancelled by then, and
 * lets the Wi-Fi session go.
 */
class RemoteViewModel(
    private val transport: HidTransport,
    private val router: RemoteRouter,
    private val companion: CompanionSession,
    private val networkSettings: NetworkSettings,
    private val companionCredentials: CompanionCredentialsStore,
    private val connectionHistory: ConnectionHistory,
    private val serialDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
) : ViewModel() {

    /** The Bluetooth link state. */
    val transportState: StateFlow<TransportState> = transport.state

    /** The Wi-Fi remote session state. */
    val companionState: StateFlow<CompanionState> = companion.state

    /** The link a press would take now: Bluetooth first, else Wi-Fi, else none (remote disabled). */
    val activeLink: StateFlow<RemoteRouter.Link?> =
        combine(transport.state, companion.state) { _, _ -> router.activeLink() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, router.activeLink())

    private val remoteShown = MutableStateFlow(false)
    private val foreground = MutableStateFlow(true)

    /** Whether a host was ever connected; false means first use, so show pairing help. */
    val hasConnectedBefore: StateFlow<Boolean> = connectionHistory.hasConnectedBefore

    private val _pairedDevices = MutableStateFlow<List<HostDevice>>(emptyList())

    /** Paired devices the user can connect to; refresh with [refreshPairedDevices]. */
    val pairedDevices: StateFlow<List<HostDevice>> = _pairedDevices.asStateFlow()

    init {
        viewModelScope.launch(serialDispatcher) {
            transport.state
                .filter { it is TransportState.Connected }
                .collect { connectionHistory.markConnected() }
        }
        viewModelScope.launch(serialDispatcher) {
            // Release on every link change; also at start when nothing is connected, which
            // resets the host after an interruption, but not when it starts out on Bluetooth.
            var first = true
            activeLink.collect { link ->
                if (!first || link != RemoteRouter.Link.Bluetooth) router.releaseAll()
                first = false
            }
        }
        viewModelScope.launch {
            combine(networkSettings.enabled, networkSettings.selectedAppleTv, companionCredentials.credentials) { enabled, selected, paired ->
                if (!enabled) null else selected?.takeIf { it in paired } ?: paired.keys.singleOrNull()
            }.distinctUntilChanged().collect { companion.target(it) }
        }
        viewModelScope.launch {
            companionCredentials.credentials.map { it.keys }.distinctUntilChanged().collect { companion.retry() }
        }
        viewModelScope.launch {
            combine(remoteShown, foreground, transport.state) { shown, fg, ble ->
                shown && fg && ble !is TransportState.Connected
            }.distinctUntilChanged().collect { companion.setWanted(it) }
        }
    }

    /** Whether the Remote tab is composed; the Wi-Fi session is held only while it is. */
    fun setRemoteShown(shown: Boolean) { remoteShown.value = shown }

    /** Whether the app is in the foreground; the Wi-Fi session closes a while after it leaves. */
    fun setForeground(inForeground: Boolean) { foreground.value = inForeground }

    /** Whether [control] can be sent on the active link (Mute has no Wi-Fi command). */
    fun supports(control: RemoteControl): Boolean = router.supports(control)

    /** Starts (or retries) the transport, e.g. after permissions were granted. */
    fun start() = serial {
        transport.start()
        _pairedDevices.value = transport.bondedDevices()
    }

    /** Re-reads the phone's paired devices into [pairedDevices]; empty without permission. */
    fun refreshPairedDevices() = serial { _pairedDevices.value = transport.bondedDevices() }

    /** Finger down on [control]: held until [release]. */
    fun press(control: RemoteControl) = serial { router.press(control) }

    /** Finger up on [control]. */
    fun release(control: RemoteControl) = serial { router.release(control) }

    /** A single click, e.g. a swipe step or an accessibility click. */
    fun tap(control: RemoteControl) = serial { router.tap(control) }

    /** A long press without a finger to time it (accessibility long-click). */
    fun hold(control: RemoteControl) = serial {
        router.press(control)
        delay(LONG_PRESS_MILLIS)
        router.release(control)
    }

    /** Releases every held control; call when the screen stops receiving input. */
    fun releaseAll() = serial { router.releaseAll() }

    private fun serial(block: suspend () -> Unit) {
        viewModelScope.launch(serialDispatcher) { block() }
    }

    override fun onCleared() {
        companion.setWanted(false)
        applicationScope.launch(serialDispatcher) { router.releaseAll() }
    }

    companion object {
        /** How long an accessibility long-click holds a control; tvOS long presses need ~1 s. */
        /** Long enough for tvOS to treat Home as a hold (Control Center); verified at 1.5 s. */
        const val LONG_PRESS_MILLIS = 1_500L

        /** Builds the ViewModel from the app's [org.ghostsinthelab.app.ringomote.AppContainer]. */
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RingomoteApplication).container
                RemoteViewModel(
                    container.transport,
                    container.remoteRouter,
                    container.companionSession,
                    container.networkSettings,
                    container.companionCredentials,
                    container.connectionHistory,
                    container.serialDispatcher,
                    container.applicationScope,
                )
            }
        }
    }
}
