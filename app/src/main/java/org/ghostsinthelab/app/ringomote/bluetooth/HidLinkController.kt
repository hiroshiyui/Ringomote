package org.ghostsinthelab.app.ringomote.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The side effects [HidLinkController] needs from the platform, so the controller itself has
 * no Android dependency and can be unit-tested with a fake.
 *
 * Asynchronous results come back through the controller's `on…` methods:
 * [openProxy] → [HidLinkController.onProxyConnected], [registerApp] →
 * [HidLinkController.onAppStatusChanged], [connect] → [HidLinkController.onConnectionStateChanged].
 */
interface HidProfilePort {
    /** Whether the phone has a Bluetooth adapter at all. */
    val hasAdapter: Boolean

    /** Whether the Bluetooth permissions needed to register and connect are granted. */
    fun hasPermission(): Boolean

    /** Whether the adapter is on. */
    fun isBluetoothOn(): Boolean

    /** Requests the HID device profile proxy; false if the request was refused. */
    fun openProxy(): Boolean

    /** Closes the proxy obtained from [openProxy], if any. */
    fun closeProxy()

    /** Registers the HID device app on the open proxy; false if the request was refused. */
    fun registerApp(): Boolean

    /** Unregisters the HID device app. */
    fun unregisterApp()

    /** Asks the host at [address] to connect; false if the request was refused. */
    fun connect(address: String): Boolean

    /** Disconnects the host at [address]. */
    fun disconnect(address: String): Boolean

    /** Describes the device at [address] for display. */
    fun hostDevice(address: String): HostDevice
}

/** A host connection change reported by the platform. */
enum class ConnectionChange { Connecting, Connected, Disconnected }

/**
 * The connection state machine behind [BluetoothHidDeviceTransport].
 *
 * It tracks three things the platform reports separately: the profile proxy (none, requested,
 * connected), the app registration, and the connected host. [start] always moves forward from
 * wherever the link is, so it doubles as "retry": it opens the proxy if there is none and none
 * is pending, and re-registers if the proxy is open but registration failed.
 *
 * Recovery rules:
 * - A proxy is requested at most once at a time; a late proxy that arrives after [stop], or one
 *   nobody asked for, is rejected so the caller closes it.
 * - Losing the proxy or Bluetooth turning off closes the proxy and forgets registration and host
 *   at once, so turning Bluetooth back on always starts over from a clean state.
 * - Events from the old proxy (registration, connection) are ignored once it is gone, and the
 *   state never becomes [TransportState.Ready] or [TransportState.Connected] while Bluetooth is
 *   off, whatever order the platform delivers events in.
 *
 * Thread-safe: every method is synchronized, because platform events arrive on binder threads,
 * the callback executor and the main thread.
 */
class HidLinkController(private val port: HidProfilePort) {

    private enum class Proxy { None, Requested, Open }

    private val _state = MutableStateFlow<TransportState>(TransportState.Stopped)

    /** The link state shown to the user. */
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var started = false
    private var proxy = Proxy.None
    private var registering = false
    private var registered = false
    private var host: String? = null

    /** Address of the connected host, or null; reports may only be sent while this is set. */
    @get:Synchronized
    val connectedHost: String?
        get() = host

    /** Starts the link, or retries whatever step is missing. Idempotent. */
    @Synchronized
    fun start() {
        started = true
        advance()
    }

    /** Disconnects, unregisters and closes the proxy. Idempotent. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        host?.let { port.disconnect(it) }
        if (registered || registering) port.unregisterApp()
        if (proxy != Proxy.None) port.closeProxy()
        reset()
        _state.value = TransportState.Stopped
    }

    /** Asks the host at [address] to connect; false unless the app is registered. */
    @Synchronized
    fun connect(address: String): Boolean =
        started && registered && port.hasPermission() && port.connect(address)

    /** Disconnects the current host; false if there is none. */
    @Synchronized
    fun disconnect(): Boolean {
        val address = host ?: return false
        return port.disconnect(address)
    }

    /**
     * The proxy requested by [HidProfilePort.openProxy] is available. Returns false if it is
     * not wanted (stopped meanwhile, or never requested); the caller must then close it.
     */
    @Synchronized
    fun onProxyConnected(): Boolean {
        if (!started || proxy != Proxy.Requested) return false
        proxy = Proxy.Open
        advance()
        return true
    }

    /** The proxy went away (Bluetooth stack restart or turned off). */
    @Synchronized
    fun onProxyDisconnected() {
        if (proxy == Proxy.None) return
        port.closeProxy()
        reset()
        if (started) advance()
    }

    /** Registration result; [pluggedHost] is a host we were virtually cabled to before. */
    @Synchronized
    fun onAppStatusChanged(registered: Boolean, pluggedHost: String?) {
        if (!started || proxy != Proxy.Open) return
        registering = false
        this.registered = registered
        if (!registered) {
            host = null
            _state.value = TransportState.RegistrationFailed
            return
        }
        if (host == null) showReady()
        if (pluggedHost != null && port.hasPermission()) port.connect(pluggedHost)
    }

    /** A host connection changed. */
    @Synchronized
    fun onConnectionStateChanged(address: String, change: ConnectionChange) {
        if (!started || !registered || !port.isBluetoothOn()) return
        when (change) {
            ConnectionChange.Connected -> {
                host = address
                _state.value = TransportState.Connected(port.hostDevice(address))
            }

            ConnectionChange.Connecting -> if (host == null) {
                _state.value = TransportState.Connecting(port.hostDevice(address))
            }

            ConnectionChange.Disconnected -> if (host == null || host == address) {
                host = null
                showReady()
            }
        }
    }

    /** Bluetooth is turning off or off: drop everything tied to the old adapter session. */
    @Synchronized
    fun onBluetoothOff() {
        if (proxy != Proxy.None) port.closeProxy()
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
            proxy == Proxy.None -> if (port.openProxy()) {
                proxy = Proxy.Requested
                TransportState.Registering
            } else {
                TransportState.Unsupported
            }

            proxy == Proxy.Requested || registering -> TransportState.Registering
            !registered -> if (port.registerApp()) {
                registering = true
                TransportState.Registering
            } else {
                TransportState.RegistrationFailed
            }

            else -> _state.value // Registered: keep Ready / Connecting / Connected.
        }
    }

    private fun showReady() {
        _state.value = if (port.isBluetoothOn()) TransportState.Ready else TransportState.BluetoothOff
    }

    private fun reset() {
        proxy = Proxy.None
        registering = false
        registered = false
        host = null
    }
}
