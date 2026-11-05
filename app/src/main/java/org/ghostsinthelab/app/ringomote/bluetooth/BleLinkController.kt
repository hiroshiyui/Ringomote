package org.ghostsinthelab.app.ringomote.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The side effects [BleLinkController] needs from the platform for BLE HID over GATT, so the
 * controller has no Android dependency and can be unit-tested with a fake.
 *
 * Asynchronous results come back through the controller's `on…` methods:
 * [openServer] → [BleLinkController.onServicesAdded] / [BleLinkController.onServicesFailed],
 * [startAdvertising] → [BleLinkController.onAdvertisingStarted] /
 * [BleLinkController.onAdvertisingFailed], [connect] and the host's own connections →
 * [BleLinkController.onHostConnected] / [BleLinkController.onHostDisconnected], and the host's
 * subscription to the input reports → [BleLinkController.onHostSubscribed].
 */
interface BlePeripheralPort {
    /** Whether the phone has a Bluetooth adapter that can advertise over BLE. */
    val hasAdapter: Boolean

    /** Whether the Bluetooth permissions needed to advertise and connect are granted. */
    fun hasPermission(): Boolean

    /** Whether the adapter is on. */
    fun isBluetoothOn(): Boolean

    /** Opens the GATT server and starts adding the HID services; false if refused outright. */
    fun openServer(): Boolean

    /** Closes the GATT server opened by [openServer], disconnecting any host. */
    fun closeServer()

    /** Starts advertising the HID service; false if refused outright. */
    fun startAdvertising(): Boolean

    /** Stops advertising. */
    fun stopAdvertising()

    /** Asks the server to connect to the bonded host at [address]; false if refused. */
    fun connect(address: String): Boolean

    /** Drops the connection to the host at [address]. */
    fun disconnect(address: String)

    /** Whether the host at [address] is bonded; a bonded host keeps its subscriptions. */
    fun isBonded(address: String): Boolean

    /** Describes the host at [address] for display. */
    fun hostDevice(address: String): HostDevice

    /** The GATT handle layout saved when a host last subscribed, or null if none was saved. */
    fun pairedLayout(): List<Int>?

    /** Persists [handles] as the layout the subscribed host knows. */
    fun savePairedLayout(handles: List<Int>)

    /** The server came up with [actual] handles instead of the saved [expected]; for the log. */
    fun noteLayoutMismatch(expected: List<Int>, actual: List<Int>, reopening: Boolean)
}

/**
 * The connection state machine behind [BleHidTransport].
 *
 * A BLE HID peripheral goes through: open the GATT server and add its services, advertise,
 * accept a host connection, and wait for the host to subscribe to the input reports. Only
 * then can reports be sent, so [TransportState.Connected] means "subscribed", not merely
 * "link up". A host that bonded earlier keeps its subscription across reconnections (the
 * Apple TV does not write it again), so a bonded host counts as subscribed on connection.
 *
 * [start] always moves forward from wherever the link is, so it doubles as "retry".
 *
 * Recovery rules:
 * - Losing Bluetooth closes the server and forgets everything, so turning it back on starts
 *   over from a clean state; events from the old server are ignored once it is gone.
 * - Advertising is stopped while a host is connected (tvOS keeps one link; a second host
 *   would steal it) and resumed when the host leaves.
 * - A bonded host may connect the instant the server exists, before its services are added
 *   (seen on hardware: 3 ms after the app restarted). Such a host is kept and shown as
 *   Connecting until [onServicesAdded], then as Connected; advertising is not started for it.
 * - A connection the phone asked for ([connect]) is pending until the host reports
 *   connected; if the host disconnects or the user cancels before that, the link goes back
 *   to Ready, so a failed phone-side connect cannot leave the UI stuck on Connecting.
 * - The state never becomes [TransportState.Ready] or [TransportState.Connected] while
 *   Bluetooth is off, whatever order the platform delivers events in.
 * - Handle layout: Android hands out GATT attribute handles from a pool shared with other
 *   apps, so the services can land on different handles each time the server opens, while a
 *   bonded Apple TV keeps using the handles it saw at pairing time and never rediscovers
 *   (verified 2026-11-05: keys stop working after an app restart moved the HID service).
 *   The layout at subscription time is saved through the port; when a later server comes up
 *   with a different one, the server is closed and reopened up to [MAX_LAYOUT_REOPENS] times
 *   to try for the saved handles, and if that fails a bonded host is shown as
 *   [TransportState.Resyncing] instead of Connected. A host that writes its subscription
 *   again has rediscovered, so that saves the new layout and clears the condition.
 * - A host connected before the services existed (this process came up under a link the
 *   previous one left open) is also shown as [TransportState.Resyncing]: when the old
 *   process died the stack sent Service Changed, and tvOS then drops the keyboard and does
 *   not rediscover it on the same link, even with identical handles (verified 2026-11-05).
 *   The phone cannot drop the link (a server-side disconnect only detaches this server, the
 *   TV as central keeps the link), so the UI tells the user to toggle Bluetooth; after that
 *   the TV reconnects, re-checks and subscribes again on its own, without re-pairing.
 *   [org.ghostsinthelab.app.ringomote.service.RemoteService] exists so this rarely happens.
 *
 * Thread-safe: every method is synchronized, because platform events arrive on binder threads
 * and the main thread.
 */
class BleLinkController(private val port: BlePeripheralPort) {

    private enum class Server { None, Opening, Open }

    private val _state = MutableStateFlow<TransportState>(TransportState.Stopped)

    /** The link state shown to the user. */
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var started = false
    private var server = Server.None
    private var advertising = false
    private var host: String? = null
    private var subscribed = false

    /** Whether [subscribed] came from a descriptor write this session, not from the bond. */
    private var subscribedByWrite = false

    /** Handles of this server's services, once added. */
    private var layout: List<Int>? = null

    /** True when [layout] differs from the saved one and reopening did not fix it. */
    private var layoutStale = false
    private var layoutReopens = 0

    /** A host connected before the services were added, waiting for them. */
    private var hostBeforeServices: String? = null

    /** True when the current host saw the services change under it; see the class doc. */
    private var hostNeedsResync = false

    /** A host the phone asked to connect to and has not heard back from yet. */
    private var pendingConnect: String? = null

    /** Address of the host subscribed to input reports, or null while reports cannot be sent. */
    @get:Synchronized
    val connectedHost: String?
        get() = host?.takeIf { subscribed && !cachedSubscriptionIsStale() }

    /** A bonded host trusting a cache this server no longer matches cannot receive reports. */
    private fun cachedSubscriptionIsStale() = (layoutStale || hostNeedsResync) && !subscribedByWrite

    /** Starts the link, or retries whatever step is missing. Idempotent. */
    @Synchronized
    fun start() {
        started = true
        advance()
    }

    /** Disconnects, stops advertising and closes the server. Idempotent. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        host?.let { port.disconnect(it) }
        if (advertising) port.stopAdvertising()
        if (server != Server.None) port.closeServer()
        reset()
        _state.value = TransportState.Stopped
    }

    /** Asks the bonded host at [address] to connect; false unless the server is open. */
    @Synchronized
    fun connect(address: String): Boolean {
        if (!started || server != Server.Open || !port.hasPermission()) return false
        if (!port.connect(address)) return false
        if (host == null) {
            pendingConnect = address
            _state.value = TransportState.Connecting(port.hostDevice(address))
        }
        return true
    }

    /** Disconnects the current host, or cancels a pending connect; false if there is neither. */
    @Synchronized
    fun disconnect(): Boolean {
        host?.let { port.disconnect(it); return true }
        val pending = pendingConnect ?: return false
        port.disconnect(pending)
        pendingConnect = null
        if (started && server == Server.Open) advance()
        return true
    }

    /**
     * The server opened by [BlePeripheralPort.openServer] has all its services, at [handles]
     * (one per service, in the order added; empty when the platform does not say).
     */
    @Synchronized
    fun onServicesAdded(handles: List<Int> = emptyList()) {
        if (!started || server != Server.Opening) return
        layout = handles
        val expected = port.pairedLayout()
        if (expected != null && handles.isNotEmpty() && handles != expected) {
            val reopening = layoutReopens < MAX_LAYOUT_REOPENS
            port.noteLayoutMismatch(expected, handles, reopening)
            if (reopening) {
                layoutReopens++
                port.closeServer()
                server = Server.None
                host = null
                hostBeforeServices = null
                subscribed = false
                subscribedByWrite = false
                advertising = false
                advance()
                return
            }
            layoutStale = true
        } else {
            layoutStale = false
        }
        layoutReopens = 0
        server = Server.Open
        val early = hostBeforeServices
        hostBeforeServices = null
        if (early != null && early == host) hostNeedsResync = true
        advance()
    }

    /** Adding a service failed; the server is unusable. */
    @Synchronized
    fun onServicesFailed() {
        if (server == Server.None) return
        port.closeServer()
        reset()
        if (started) _state.value = TransportState.RegistrationFailed
    }

    /** Advertising is on. */
    @Synchronized
    fun onAdvertisingStarted() {
        if (!started || server != Server.Open) return
        advertising = true
        if (host == null) showReady()
    }

    /** Advertising could not start (for example, another app holds all advertising slots). */
    @Synchronized
    fun onAdvertisingFailed() {
        if (!started || server != Server.Open) return
        advertising = false
        if (host == null) _state.value = TransportState.RegistrationFailed
    }

    /** A host connected to the server. */
    @Synchronized
    fun onHostConnected(address: String) {
        if (!started || server == Server.None || !port.isBluetoothOn()) return
        if (host != null && host != address) {
            port.disconnect(address) // One host at a time.
            return
        }
        host = address
        pendingConnect = null
        subscribed = port.isBonded(address)
        subscribedByWrite = false
        hostNeedsResync = false
        if (advertising) {
            port.stopAdvertising()
            advertising = false
        }
        if (server == Server.Open) showHost() else hostBeforeServices = address // Registering until the services exist
    }

    /** The host wrote the input report's client configuration (notifications on). */
    @Synchronized
    fun onHostSubscribed(address: String) {
        if (host != address) return
        subscribed = true
        subscribedByWrite = true
        // A fresh subscription means the host discovered this layout: remember it.
        layout?.takeIf { it.isNotEmpty() }?.let(port::savePairedLayout)
        layoutStale = false
        showHost()
    }

    /** The host at [address] disconnected. */
    @Synchronized
    fun onHostDisconnected(address: String) {
        if (pendingConnect == address) pendingConnect = null
        else if (host != address) return
        host = null
        subscribed = false
        if (hostBeforeServices == address) hostBeforeServices = null
        hostNeedsResync = false
        if (!started || server == Server.None) return
        advance()
    }

    /** Bluetooth is turning off or off: drop everything tied to the old adapter session. */
    @Synchronized
    fun onBluetoothOff() {
        if (server != Server.None) port.closeServer()
        reset()
        if (started) _state.value = TransportState.BluetoothOff
    }

    /** Bluetooth is on again: start over if the link is wanted. */
    @Synchronized
    fun onBluetoothOn() {
        if (started) advance()
    }

    /** Moves the link one step forward from wherever it is. */
    private fun advance() {
        _state.value = when {
            !port.hasAdapter -> TransportState.Unsupported
            !port.hasPermission() -> TransportState.PermissionRequired
            !port.isBluetoothOn() -> TransportState.BluetoothOff
            server == Server.None -> if (port.openServer()) {
                server = Server.Opening
                TransportState.Registering
            } else {
                TransportState.Unsupported
            }

            server == Server.Opening -> TransportState.Registering
            host != null -> return showHost()
            advertising -> TransportState.Ready
            port.startAdvertising() -> TransportState.Registering
            else -> TransportState.RegistrationFailed
        }
    }

    private fun showHost() {
        val address = host ?: return
        _state.value = when {
            !port.isBluetoothOn() -> TransportState.BluetoothOff
            subscribed && cachedSubscriptionIsStale() -> TransportState.Resyncing(port.hostDevice(address))
            subscribed -> TransportState.Connected(port.hostDevice(address))
            else -> TransportState.Connecting(port.hostDevice(address))
        }
    }

    private fun showReady() {
        _state.value = if (port.isBluetoothOn()) TransportState.Ready else TransportState.BluetoothOff
    }

    private fun reset() {
        server = Server.None
        advertising = false
        host = null
        subscribed = false
        subscribedByWrite = false
        pendingConnect = null
        layout = null
        layoutStale = false
        layoutReopens = 0
        hostBeforeServices = null
        hostNeedsResync = false
    }

    companion object {
        /** How many times to reopen the server hoping for the saved handle layout. */
        const val MAX_LAYOUT_REOPENS = 3
    }
}
