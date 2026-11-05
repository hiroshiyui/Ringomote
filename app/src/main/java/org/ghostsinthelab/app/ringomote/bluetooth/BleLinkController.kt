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
 * [BleLinkController.onAdvertisingFailed], the host's own connections →
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

    /** Opens the GATT server, without services yet; false if refused outright. */
    fun openServer(): Boolean

    /** Starts adding the HID services to the open server; false if refused outright. */
    fun addServices(): Boolean

    /** Runs [action] after [delayMillis], on any thread. */
    fun after(delayMillis: Long, action: () -> Unit)

    /** Closes the GATT server opened by [openServer], disconnecting any host. */
    fun closeServer()

    /** Starts advertising the HID service; false if refused outright. */
    fun startAdvertising(): Boolean

    /** Stops advertising. */
    fun stopAdvertising()

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
    fun noteLayoutMismatch(expected: List<Int>, actual: List<Int>)

    /**
     * Takes the whole Bluetooth link to [address] down, not just this server's attachment,
     * so that the host reconnects and reads the services afresh. Best effort: the outcome
     * shows up as [BleLinkController.onHostDisconnected], or does not.
     */
    fun dropLink(address: String)
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
 * - The state never becomes [TransportState.Ready] or [TransportState.Connected] while
 *   Bluetooth is off, whatever order the platform delivers events in.
 * - Handle layout: Android hands out GATT attribute handles from a pool shared with other
 *   apps, so the services can land on different handles each time the server opens, while a
 *   bonded Apple TV keeps using the handles it saw at pairing time and never rediscovers
 *   (verified 2026-11-05: keys stop working after an app restart moved the HID service).
 *   The layout at subscription time is saved through the port and a later mismatch is
 *   logged, for diagnostics only: on a fresh link the TV rediscovers the services (seen in
 *   the stack log 2026-11-05) and, being bonded, need not write its subscription again. The
 *   server is never closed and reopened to chase old handles: a host may already be attached,
 *   and deleting services under its link is exactly what makes tvOS drop the keyboard.
 * - A host connected before the services existed (this process came up under a link the
 *   previous one left open) is also shown as [TransportState.Resyncing]: when the old
 *   process died the stack sent Service Changed, and tvOS then drops the keyboard and does
 *   not rediscover it on the same link, even with identical handles (verified 2026-11-05).
 *   The phone cannot fix this by itself: a server-side disconnect only detaches this server,
 *   and a GATT-client disconnect does take the link down (tried 2026-11-05) but the TV then
 *   reconnects without re-enumerating, so the keys stay dead while the link looks fine.
 *   Left alone, tvOS recovers by itself: some 20–30 s after the Service Changed it drops
 *   the link, re-makes it and re-enumerates, writing its subscriptions again (seen twice on
 *   2026-11-05). So the controller does nothing but wait and show [TransportState.Resyncing]
 *   meanwhile; hurrying the TV prevented the recovery every time it was tried. The same
 *   race happens after a Bluetooth toggle: the TV connects within milliseconds of the
 *   adapter coming up, before the services exist.
 *   [org.ghostsinthelab.app.ringomote.service.RemoteService] exists so this rarely happens.
 *   GATT robust caching (Bluetooth 5.1) cannot be used to make the TV look again: Android's
 *   Generic Attribute service exposes Server/Client Supported Features and Database Hash,
 *   but the HCI snoop capture of 2026-11-06 shows tvOS never reading the hash or writing
 *   the Client Supported Features bit; on a stale link it re-reads only that service and
 *   the device name, i.e. it caches as a legacy client that acts on Service Changed alone.
 *
 * Thread-safe: every method is synchronized, because platform events arrive on binder threads
 * and the main thread.
 */
class BleLinkController(private val port: BlePeripheralPort) {

    private enum class Server { None, Opening, Adding, Open }

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


    /** A host connected before the services were added, waiting for them. */
    private var hostBeforeServices: String? = null

    /** Bumped whenever the server is (re)opened, so a stale grace timer is ignored. */
    private var serverGeneration = 0

    /** True when the current host saw the services change under it; see the class doc. */
    private var hostNeedsResync = false

    /** Address of the host subscribed to input reports, or null while reports cannot be sent. */
    @get:Synchronized
    val connectedHost: String?
        get() = host?.takeIf { subscribed && !cachedSubscriptionIsStale() }

    /** A bonded host that saw the services appear under its link cannot receive reports. */
    private fun cachedSubscriptionIsStale() = hostNeedsResync && !subscribedByWrite

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

    /** Disconnects the current host; false if there is none. */
    @Synchronized
    fun disconnect(): Boolean {
        val current = host ?: return false
        port.disconnect(current)
        return true
    }

    /**
     * The server opened by [BlePeripheralPort.openServer] has all its services, at [handles]
     * (one per service, in the order added; empty when the platform does not say).
     */
    @Synchronized
    private fun onGraceElapsed(generation: Int) {
        if (generation != serverGeneration || server != Server.Opening) return
        if (host == null) startAddingServices()
        // else: an early host is being dropped; the services follow its disconnect.
    }

    private fun startAddingServices() {
        if (port.addServices()) server = Server.Adding else onServicesFailed()
    }

    @Synchronized
    fun onServicesAdded(handles: List<Int> = emptyList()) {
        if (!started || server != Server.Adding) return
        layout = handles
        val expected = port.pairedLayout()
        if (expected != null && handles.isNotEmpty() && handles != expected) port.noteLayoutMismatch(expected, handles)
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
        subscribed = port.isBonded(address)
        subscribedByWrite = false
        hostNeedsResync = false
        if (advertising) {
            port.stopAdvertising()
            advertising = false
        }
        when (server) {
            Server.Open -> showHost()
            Server.Opening -> {
                // The host attached before the services exist (this process came up, or the
                // adapter did, under a link the TV keeps). Drop the link now, and add the
                // services only once it is down, so that the TV's reconnection finds the
                // complete table, as a first pairing does; see the class doc.
                hostBeforeServices = address
                if (port.isBonded(address)) port.dropLink(address) else startAddingServices()
            }
            Server.Adding -> hostBeforeServices = address // raced the adds; Resyncing later
            Server.None -> Unit
        }
    }

    /** The host wrote the input report's client configuration (notifications on). */
    @Synchronized
    fun onHostSubscribed(address: String) {
        if (host != address) return
        subscribed = true
        subscribedByWrite = true
        // A fresh subscription means the host discovered this layout: remember it.
        layout?.takeIf { it.isNotEmpty() }?.let(port::savePairedLayout)
        showHost()
    }

    /** The host at [address] disconnected. */
    @Synchronized
    fun onHostDisconnected(address: String) {
        if (host != address) return
        host = null
        subscribed = false
        if (hostBeforeServices == address) hostBeforeServices = null
        hostNeedsResync = false
        if (!started || server == Server.None) return
        if (server == Server.Opening) {
            // The early host is gone: add the services now, before it comes back.
            startAddingServices()
            return
        }
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
                // Open and empty: give a host that was attached before a moment to show up,
                // so it can be dropped before the services exist; else add them.
                server = Server.Opening
                val generation = ++serverGeneration
                port.after(EARLY_HOST_GRACE_MILLIS) { onGraceElapsed(generation) }
                TransportState.Registering
            } else {
                TransportState.Unsupported
            }

            server == Server.Opening || server == Server.Adding -> TransportState.Registering
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
        layout = null
        hostBeforeServices = null
        hostNeedsResync = false
        serverGeneration++
    }

    companion object {
        /** How long after opening the server to wait for a host that was attached before. */
        const val EARLY_HOST_GRACE_MILLIS = 500L
    }
}
