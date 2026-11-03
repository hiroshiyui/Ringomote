package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.bluetooth.HidTransport
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.remote.RemoteSession

/**
 * State and actions for the remote screen.
 *
 * All input goes through one serial dispatcher, so reports reach the host in the order the
 * user produced them and binder calls stay off the main thread.
 *
 * The ViewModel starts the transport on request ([start], after permissions are granted) and
 * stops it in [onCleared], i.e. when the user leaves the app for good, releasing held keys
 * first.
 */
class RemoteViewModel(
    private val transport: HidTransport,
    private val session: RemoteSession,
) : ViewModel() {

    private val inputDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** The Bluetooth link state. */
    val transportState: StateFlow<TransportState> = transport.state

    private val _pairedDevices = MutableStateFlow<List<HostDevice>>(emptyList())

    /** Paired devices the user can connect to; refresh with [refreshPairedDevices]. */
    val pairedDevices: StateFlow<List<HostDevice>> = _pairedDevices.asStateFlow()

    /** Starts (or retries) the transport, e.g. after permissions were granted. */
    fun start() {
        transport.start()
        refreshPairedDevices()
    }

    /** Re-reads the phone's paired devices into [pairedDevices]; empty without permission. */
    fun refreshPairedDevices() {
        _pairedDevices.value = transport.bondedDevices()
    }

    /**
     * Asks the transport to connect to [device]. The outcome arrives through [transportState]
     * (Connecting, then Connected or back to Ready).
     */
    fun connect(device: HostDevice) {
        viewModelScope.launch(inputDispatcher) { transport.connect(device.address) }
    }

    /** Releases held controls first, so nothing stays pressed on the host, then disconnects. */
    fun disconnect() {
        viewModelScope.launch(inputDispatcher) {
            session.releaseAll()
            transport.disconnect()
        }
    }

    /** Finger down on [control]: held until [release]. */
    fun press(control: RemoteControl) = input { session.press(control) }

    /** Finger up on [control]. */
    fun release(control: RemoteControl) = input { session.release(control) }

    /** A single click, e.g. a swipe step or an accessibility click. */
    fun tap(control: RemoteControl) = input { session.tap(control) }

    /** A long press without a finger to time it (accessibility long-click). */
    fun hold(control: RemoteControl) = input {
        session.press(control)
        delay(LONG_PRESS_MILLIS)
        session.release(control)
    }

    /** Releases every held control; call when the screen stops receiving input. */
    fun releaseAll() = input { session.releaseAll() }

    private fun input(block: suspend () -> Unit) {
        viewModelScope.launch(inputDispatcher) { block() }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so release synchronously.
        session.releaseAll()
        transport.stop()
    }

    companion object {
        /** How long an accessibility long-click holds a control; tvOS long presses need ~1 s. */
        const val LONG_PRESS_MILLIS = 1_200L

        /** Builds the ViewModel from the app's [org.ghostsinthelab.app.ringomote.AppContainer]. */
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RingomoteApplication
                RemoteViewModel(app.container.transport, app.container.session)
            }
        }
    }
}
