package org.ghostsinthelab.app.ringomote.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [HidLinkController], the connection state machine behind the Bluetooth transport,
 * against [FakePort], which records every platform call and lets each test decide when the
 * asynchronous platform events arrive and in what order.
 *
 * The scenarios come from the code review: retry after a failed registration, duplicate proxy
 * requests, Bluetooth turning off and on, and late events from a proxy that is already gone.
 */
class HidLinkControllerTest {

    private val tv = "00:11:22:33:44:55"
    private val port = FakePort()
    private val controller = HidLinkController(port)

    /** Drives the controller to Ready: start, proxy arrives, registration succeeds. */
    private fun startRegistered() {
        controller.start()
        controller.onProxyConnected()
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
    }

    /** Happy path: start requests a proxy, registers on it, and ends Ready. */
    @Test
    fun startRegistersAndBecomesReady() {
        controller.start()
        assertEquals(TransportState.Registering, controller.state.value)
        assertEquals(1, port.openProxyCalls)

        assertTrue(controller.onProxyConnected())
        assertEquals(1, port.registerCalls)

        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** Preconditions are reported in order: no adapter, no permission, Bluetooth off. */
    @Test
    fun startReportsMissingPreconditions() {
        port.hasPermission = false
        controller.start()
        assertEquals(TransportState.PermissionRequired, controller.state.value)

        port.hasPermission = true
        port.bluetoothOn = false
        controller.start()
        assertEquals(TransportState.BluetoothOff, controller.state.value)
        assertEquals(0, port.openProxyCalls)
    }

    /** Review item 1: Retry after a failed registration registers again on the open proxy. */
    @Test
    fun retryAfterRegistrationFailureRegistersAgain() {
        controller.start()
        controller.onProxyConnected()
        controller.onAppStatusChanged(registered = false, pluggedHost = null)
        assertEquals(TransportState.RegistrationFailed, controller.state.value)

        controller.start() // the Retry button
        assertEquals(2, port.registerCalls)
        assertEquals(1, port.openProxyCalls)
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** Review item 1: a registration request refused outright can also be retried. */
    @Test
    fun retryAfterRefusedRegistrationRequest() {
        port.registerAccepted = false
        controller.start()
        controller.onProxyConnected()
        assertEquals(TransportState.RegistrationFailed, controller.state.value)

        port.registerAccepted = true
        controller.start()
        assertEquals(TransportState.Registering, controller.state.value)
        assertEquals(2, port.registerCalls)
    }

    /** Review item 2: start() again while the proxy is pending does not request a second one. */
    @Test
    fun repeatedStartWhilePendingRequestsOneProxy() {
        controller.start()
        controller.start()
        controller.start()
        assertEquals(1, port.openProxyCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** Review item 2: start() again while registering does not register twice. */
    @Test
    fun repeatedStartWhileRegisteringRegistersOnce() {
        controller.start()
        controller.onProxyConnected()
        controller.start()
        assertEquals(1, port.registerCalls)
    }

    /** Review item 2: a proxy that arrives after stop() is rejected so the caller closes it. */
    @Test
    fun proxyArrivingAfterStopIsRejected() {
        controller.start()
        controller.stop()
        assertFalse(controller.onProxyConnected())
        assertEquals(0, port.registerCalls)
        assertEquals(TransportState.Stopped, controller.state.value)
    }

    /** Review item 2: an unrequested second proxy callback is rejected. */
    @Test
    fun unrequestedProxyIsRejected() {
        controller.start()
        assertTrue(controller.onProxyConnected())
        assertFalse(controller.onProxyConnected())
        assertEquals(1, port.registerCalls)
    }

    /** Review items 3 and 5: Bluetooth off closes the proxy and forgets registration and host. */
    @Test
    fun bluetoothOffClosesProxyAndClearsHost() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)

        port.bluetoothOn = false
        controller.onBluetoothOff()
        assertEquals(TransportState.BluetoothOff, controller.state.value)
        assertEquals(1, port.closeProxyCalls)
        assertNull(controller.connectedHost)
    }

    /** Review item 3: turning Bluetooth back on starts over, even before the proxy-lost event. */
    @Test
    fun bluetoothOnAgainReopensProxyAndRegisters() {
        startRegistered()
        port.bluetoothOn = false
        controller.onBluetoothOff()

        port.bluetoothOn = true
        controller.onBluetoothOn()
        assertEquals(2, port.openProxyCalls)
        assertEquals(TransportState.Registering, controller.state.value)

        controller.onProxyConnected()
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertEquals(TransportState.Ready, controller.state.value)
        assertEquals(2, port.registerCalls)
    }

    /** Review item 3: a late proxy-lost event after Bluetooth off does not close twice. */
    @Test
    fun lateProxyLostAfterBluetoothOffIsIgnored() {
        startRegistered()
        port.bluetoothOn = false
        controller.onBluetoothOff()
        controller.onProxyDisconnected()
        assertEquals(1, port.closeProxyCalls)
        assertEquals(TransportState.BluetoothOff, controller.state.value)
    }

    /** Review item 3: a late "disconnected" after Bluetooth off does not show Ready. */
    @Test
    fun lateDisconnectAfterBluetoothOffKeepsBluetoothOff() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        port.bluetoothOn = false
        controller.onBluetoothOff()

        controller.onConnectionStateChanged(tv, ConnectionChange.Disconnected)
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertEquals(TransportState.BluetoothOff, controller.state.value)
    }

    /** Review item 3: a disconnect event racing ahead of the off broadcast does not show Ready. */
    @Test
    fun disconnectWhileAdapterOffDoesNotShowReady() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        port.bluetoothOn = false // adapter already off, broadcast not delivered yet
        controller.onConnectionStateChanged(tv, ConnectionChange.Disconnected)
        assertTrue(controller.state.value !is TransportState.Ready)
    }

    /** Review item 5: losing the proxy (stack restart) closes it and re-requests a new one. */
    @Test
    fun proxyLostWhileOnClosesAndReopens() {
        startRegistered()
        controller.onProxyDisconnected()
        assertEquals(1, port.closeProxyCalls)
        assertEquals(2, port.openProxyCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** Losing registration (another app took over) drops the host and allows retry. */
    @Test
    fun registrationLostDropsHost() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        controller.onAppStatusChanged(registered = false, pluggedHost = null)
        assertEquals(TransportState.RegistrationFailed, controller.state.value)
        assertNull(controller.connectedHost)
    }

    /** Connection events move between Connecting, Connected and back to Ready. */
    @Test
    fun connectionLifecycle() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connecting)
        assertEquals(TransportState.Connecting(HostDevice(tv, "TV")), controller.state.value)
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        assertEquals(TransportState.Connected(HostDevice(tv, "TV")), controller.state.value)
        assertEquals(tv, controller.connectedHost)
        controller.onConnectionStateChanged(tv, ConnectionChange.Disconnected)
        assertEquals(TransportState.Ready, controller.state.value)
        assertNull(controller.connectedHost)
    }

    /** A disconnect from a different device does not drop the connected host. */
    @Test
    fun otherDeviceDisconnectKeepsHost() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        controller.onConnectionStateChanged("66:77:88:99:AA:BB", ConnectionChange.Disconnected)
        assertEquals(tv, controller.connectedHost)
    }

    /** A host we were virtually cabled to is reconnected right after registration. */
    @Test
    fun pluggedHostIsReconnected() {
        controller.start()
        controller.onProxyConnected()
        controller.onAppStatusChanged(registered = true, pluggedHost = tv)
        assertEquals(listOf(tv), port.connected)
    }

    /** connect() is refused until registered, then forwarded. */
    @Test
    fun connectRequiresRegistration() {
        controller.start()
        assertFalse(controller.connect(tv))
        controller.onProxyConnected()
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertTrue(controller.connect(tv))
        assertEquals(listOf(tv), port.connected)
    }

    /** stop() disconnects the host, unregisters, closes the proxy and ignores later events. */
    @Test
    fun stopTearsDownAndIgnoresLateEvents() {
        startRegistered()
        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        controller.stop()

        assertEquals(listOf(tv), port.disconnected)
        assertEquals(1, port.unregisterCalls)
        assertEquals(1, port.closeProxyCalls)
        assertEquals(TransportState.Stopped, controller.state.value)

        controller.onConnectionStateChanged(tv, ConnectionChange.Connected)
        controller.onAppStatusChanged(registered = true, pluggedHost = null)
        assertEquals(TransportState.Stopped, controller.state.value)
        assertNull(controller.connectedHost)
    }

    /** After stop(), start() works again from scratch. */
    @Test
    fun startAfterStopStartsOver() {
        startRegistered()
        controller.stop()
        controller.start()
        assertEquals(2, port.openProxyCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** Records platform calls; flags let a test simulate adapter and permission changes. */
    private class FakePort : HidProfilePort {
        override var hasAdapter = true
        var hasPermission = true
        var bluetoothOn = true
        var registerAccepted = true

        var openProxyCalls = 0
        var closeProxyCalls = 0
        var registerCalls = 0
        var unregisterCalls = 0
        val connected = mutableListOf<String>()
        val disconnected = mutableListOf<String>()

        override fun hasPermission() = hasPermission
        override fun isBluetoothOn() = bluetoothOn
        override fun openProxy(): Boolean { openProxyCalls++; return true }
        override fun closeProxy() { closeProxyCalls++ }
        override fun registerApp(): Boolean { registerCalls++; return registerAccepted }
        override fun unregisterApp() { unregisterCalls++ }
        override fun connect(address: String): Boolean { connected += address; return true }
        override fun disconnect(address: String): Boolean { disconnected += address; return true }
        override fun hostDevice(address: String) = HostDevice(address, "TV")
    }
}
