package org.ghostsinthelab.app.ringomote.bluetooth

import kotlinx.coroutines.flow.StateFlow
import org.ghostsinthelab.app.ringomote.hid.HidReportSink

/**
 * A link that presents the phone to a host (the Apple TV) as a HID device and carries reports.
 *
 * The UI and [org.ghostsinthelab.app.ringomote.remote.RemoteSession] depend only on this
 * interface, so the Bluetooth implementation can change (classic HID today, BLE HID over GATT
 * possibly later) or be faked in tests.
 *
 * [HidReportSink.send] returns false unless [state] is [TransportState.Connected].
 */
interface HidTransport : HidReportSink {

    /** The current link state; updated from Bluetooth callbacks on any thread. */
    val state: StateFlow<TransportState>

    /**
     * Starts presenting the HID device. Idempotent. Call again after the user grants
     * Bluetooth permissions or turns Bluetooth on to retry.
     */
    fun start()

    /** Disconnects, unregisters and releases the Bluetooth resources. Idempotent. */
    fun stop()

    /** Devices already paired with the phone; empty without permission. */
    fun bondedDevices(): List<HostDevice>

    /** Asks to connect to the paired host at [address]; returns false if the request failed. */
    fun connect(address: String): Boolean

    /** Disconnects the current host, if any. */
    fun disconnect(): Boolean
}

/** A Bluetooth host the phone can control. [name] is null when Android does not know it. */
data class HostDevice(val address: String, val name: String?)

/** Link states shown to the user, in roughly the order a session goes through them. */
sealed interface TransportState {
    /** [HidTransport.start] has not been called, or [HidTransport.stop] was. */
    data object Stopped : TransportState

    /** The phone has no Bluetooth adapter or does not support the HID device profile. */
    data object Unsupported : TransportState

    /** Bluetooth permissions (Android 12+) have not been granted. */
    data object PermissionRequired : TransportState

    /** Bluetooth is off. */
    data object BluetoothOff : TransportState

    /** Waiting for the system to register the HID device. */
    data object Registering : TransportState

    /** Registration failed or was taken over by another app. */
    data object RegistrationFailed : TransportState

    /** Registered and waiting for a host to connect, or for the user to pick one. */
    data object Ready : TransportState

    /** A connection to [device] is being set up. */
    data class Connecting(val device: HostDevice) : TransportState

    /** Connected to [device]; reports can be sent. */
    data class Connected(val device: HostDevice) : TransportState
}
