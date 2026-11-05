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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.history.ConnectionHistory
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.remote.RemoteSession

/**
 * State and actions for the remote screen.
 *
 * Everything that talks to the transport runs on [serialDispatcher], shared app-wide (see
 * [org.ghostsinthelab.app.ringomote.AppContainer.serialDispatcher]): reports reach the host in
 * the order the user produced them, binder calls stay off the main thread, and start/stop
 * requests from successive screens cannot overtake each other.
 *
 * Whenever the link leaves [TransportState.Connected], held controls are released, so a finger
 * that was down when the Apple TV disconnected does not block the same button after
 * reconnecting.
 *
 * The first successful connection is recorded in [connectionHistory]; until then
 * [hasConnectedBefore] is false and the UI opens the pairing details by default.
 *
 * The ViewModel starts the transport on request ([start], after permissions are granted) but
 * never stops it: the HID device must outlive the screen, or the Apple TV drops the keyboard
 * (see [org.ghostsinthelab.app.ringomote.service.RemoteService], which keeps the process and
 * the transport alive and is the only place that stops it). [onCleared] only releases held
 * keys, in [applicationScope] because [viewModelScope] is already cancelled by then.
 */
class RemoteViewModel(
    private val transport: HidTransport,
    private val session: RemoteSession,
    private val connectionHistory: ConnectionHistory,
    private val serialDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
) : ViewModel() {

    /** The Bluetooth link state. */
    val transportState: StateFlow<TransportState> = transport.state

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
            transport.state
                .map { it is TransportState.Connected }
                .distinctUntilChanged()
                .filter { connected -> !connected }
                .collect { session.releaseAll() }
        }
    }

    /** Starts (or retries) the transport, e.g. after permissions were granted. */
    fun start() = serial {
        transport.start()
        _pairedDevices.value = transport.bondedDevices()
    }

    /** Re-reads the phone's paired devices into [pairedDevices]; empty without permission. */
    fun refreshPairedDevices() = serial { _pairedDevices.value = transport.bondedDevices() }

    /**
     * Asks the transport to connect to [device]. The outcome arrives through [transportState]
     * (Connecting, then Connected or back to Ready).
     */
    fun connect(device: HostDevice) = serial { transport.connect(device.address) }

    /** Finger down on [control]: held until [release]. */
    fun press(control: RemoteControl) = serial { session.press(control) }

    /** Finger up on [control]. */
    fun release(control: RemoteControl) = serial { session.release(control) }

    /** A single click, e.g. a swipe step or an accessibility click. */
    fun tap(control: RemoteControl) = serial { session.tap(control) }

    /** A long press without a finger to time it (accessibility long-click). */
    fun hold(control: RemoteControl) = serial {
        session.press(control)
        delay(LONG_PRESS_MILLIS)
        session.release(control)
    }

    /** Releases every held control; call when the screen stops receiving input. */
    fun releaseAll() = serial { session.releaseAll() }

    private fun serial(block: suspend () -> Unit) {
        viewModelScope.launch(serialDispatcher) { block() }
    }

    override fun onCleared() {
        applicationScope.launch(serialDispatcher) { session.releaseAll() }
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
                    container.session,
                    container.connectionHistory,
                    container.serialDispatcher,
                    container.applicationScope,
                )
            }
        }
    }
}
