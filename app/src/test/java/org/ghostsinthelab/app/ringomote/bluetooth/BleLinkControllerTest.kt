package org.ghostsinthelab.app.ringomote.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [BleLinkController], the state machine behind the BLE HID transport, against
 * [FakeBlePort], which records every platform call and lets each test deliver the asynchronous
 * platform events in the order it wants.
 *
 * The scenarios follow what an Apple TV was seen doing on hardware: it connects, pairs, then
 * subscribes to the input reports; a bonded TV reconnects without subscribing again; the link
 * drops when idle; and Bluetooth can be toggled under the app at any point.
 */
class BleLinkControllerTest {

    private val tv = "DC:56:E7:53:5E:09"
    private val other = "00:11:22:33:44:55"
    private val port = FakeBlePort()
    private val controller = BleLinkController(port)

    /** Drives the controller to Ready: start, grace elapsed, services added, advertising on. */
    private fun startAdvertising() {
        controller.start()
        port.fireTimers()
        controller.onServicesAdded()
        controller.onAdvertisingStarted()
    }

    /** A host writing its subscription saves the handle layout it discovered. */
    @Test
    fun subscriptionSavesLayout() {
        controller.start()
        port.fireTimers()
        controller.onServicesAdded(listOf(50, 55, 59))
        controller.onAdvertisingStarted()
        controller.onHostConnected(tv)
        controller.onHostSubscribed(tv)
        assertEquals(listOf(50, 55, 59), port.savedLayout)
        assertEquals(TransportState.Connected(HostDevice(tv, "Apple TV")), controller.state.value)
    }

    /** Services on other handles than the host subscribed to are only logged; the host is connected. */
    @Test
    fun movedLayoutIsLoggedOnly() {
        port.savedLayout = listOf(50, 55, 59)
        controller.start()
        port.fireTimers()
        controller.onServicesAdded(listOf(60, 65, 69))
        assertEquals(0, port.closeServerCalls) // never reopened under a possible link
        assertEquals(1, port.mismatches.size)
        controller.onAdvertisingStarted()

        port.bonded += tv
        controller.onHostConnected(tv)
        assertEquals(TransportState.Connected(HostDevice(tv, "Apple TV")), controller.state.value)
        assertEquals(tv, controller.connectedHost)
        controller.onHostSubscribed(tv)
        assertEquals(listOf(60, 65, 69), port.savedLayout)
    }

    /**
     * A bonded host attached before the services exist is dropped first; the services are
     * added only once it is gone, so its reconnection finds the complete table.
     */
    @Test
    fun earlyHostIsDroppedBeforeServicesAreAdded() {
        port.bonded += tv
        controller.start()
        controller.onHostConnected(tv)
        assertEquals(listOf(tv), port.droppedLinks)
        assertEquals(0, port.addServicesCalls)
        port.fireTimers() // grace elapses with the host still attached: still no services
        assertEquals(0, port.addServicesCalls)

        controller.onHostDisconnected(tv)
        assertEquals(1, port.addServicesCalls)
        controller.onServicesAdded(listOf(55, 60, 64))
        controller.onAdvertisingStarted()
        assertEquals(TransportState.Ready, controller.state.value)

        controller.onHostConnected(tv)
        assertEquals(TransportState.Connected(HostDevice(tv, "Apple TV")), controller.state.value)
        assertEquals(tv, controller.connectedHost)
    }

    /** With no early host, the services are added when the grace period ends. */
    @Test
    fun servicesAreAddedAfterGraceWithoutEarlyHost() {
        controller.start()
        assertEquals(0, port.addServicesCalls)
        port.fireTimers()
        assertEquals(1, port.addServicesCalls)
        controller.onServicesAdded()
        assertEquals(1, port.startAdvertisingCalls)
    }

    /** Happy path: start opens the server, advertises once the services exist, and ends Ready. */
    @Test
    fun startOpensServerAdvertisesAndBecomesReady() {
        controller.start()
        port.fireTimers()
        assertEquals(TransportState.Registering, controller.state.value)
        assertEquals(1, port.openServerCalls)
        assertEquals(0, port.startAdvertisingCalls)

        controller.onServicesAdded()
        assertEquals(1, port.startAdvertisingCalls)
        assertEquals(TransportState.Registering, controller.state.value)

        controller.onAdvertisingStarted()
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** Preconditions are reported in order: no adapter, no permission, Bluetooth off. */
    @Test
    fun startReportsMissingPreconditions() {
        port.hasAdapter = false
        controller.start()
        port.fireTimers()
        assertEquals(TransportState.Unsupported, controller.state.value)

        port.hasAdapter = true
        port.hasPermission = false
        controller.start()
        port.fireTimers()
        assertEquals(TransportState.PermissionRequired, controller.state.value)

        port.hasPermission = true
        port.bluetoothOn = false
        controller.start()
        port.fireTimers()
        assertEquals(TransportState.BluetoothOff, controller.state.value)
        assertEquals(0, port.openServerCalls)
    }

    /** A second start while the server is still opening does not open another one. */
    @Test
    fun startWhileOpeningDoesNotOpenTwice() {
        controller.start()
        port.fireTimers()
        controller.start()
        port.fireTimers()
        assertEquals(1, port.openServerCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** A refused server means the phone cannot be a peripheral. */
    @Test
    fun refusedServerIsUnsupported() {
        port.openServerResult = false
        controller.start()
        port.fireTimers()
        assertEquals(TransportState.Unsupported, controller.state.value)
    }

    /** A service that cannot be added closes the server and shows a retryable failure. */
    @Test
    fun serviceFailureClosesServerAndRetryReopens() {
        controller.start()
        port.fireTimers()
        controller.onServicesFailed()
        assertEquals(1, port.closeServerCalls)
        assertEquals(TransportState.RegistrationFailed, controller.state.value)

        controller.start()
        port.fireTimers()
        assertEquals(2, port.openServerCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** Advertising that fails to start shows a retryable failure; retry advertises again. */
    @Test
    fun advertisingFailureIsRetryable() {
        controller.start()
        port.fireTimers()
        controller.onServicesAdded()
        controller.onAdvertisingFailed()
        assertEquals(TransportState.RegistrationFailed, controller.state.value)

        controller.start()
        port.fireTimers()
        assertEquals(2, port.startAdvertisingCalls)
        assertEquals(1, port.openServerCalls)
        controller.onAdvertisingStarted()
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** First pairing: the host connects (Connecting), then subscribes (Connected). */
    @Test
    fun newHostIsConnectingUntilItSubscribes() {
        startAdvertising()
        controller.onHostConnected(tv)
        assertEquals(TransportState.Connecting(port.hostDevice(tv)), controller.state.value)
        assertNull(controller.connectedHost)
        assertEquals(1, port.stopAdvertisingCalls)

        controller.onHostSubscribed(tv)
        assertEquals(TransportState.Connected(port.hostDevice(tv)), controller.state.value)
        assertEquals(tv, controller.connectedHost)
    }

    /** A bonded host keeps its subscription, so it is Connected as soon as it connects. */
    @Test
    fun bondedHostIsConnectedOnConnection() {
        port.bonded += tv
        startAdvertising()
        controller.onHostConnected(tv)
        assertEquals(TransportState.Connected(port.hostDevice(tv)), controller.state.value)
        assertEquals(tv, controller.connectedHost)
    }

    /** When the host drops the idle link, advertising resumes and the state returns to Ready. */
    @Test
    fun hostDisconnectResumesAdvertising() {
        startAdvertising()
        controller.onHostConnected(tv)
        controller.onHostSubscribed(tv)

        controller.onHostDisconnected(tv)
        assertNull(controller.connectedHost)
        assertEquals(2, port.startAdvertisingCalls)
        assertEquals(TransportState.Registering, controller.state.value)
        controller.onAdvertisingStarted()
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** A disconnect from a host that is not ours is ignored. */
    @Test
    fun disconnectOfOtherHostIsIgnored() {
        startAdvertising()
        controller.onHostConnected(tv)
        controller.onHostSubscribed(tv)
        controller.onHostDisconnected(other)
        assertEquals(tv, controller.connectedHost)
        assertEquals(1, port.startAdvertisingCalls)
    }

    /** A second host is turned away while one is connected. */
    @Test
    fun secondHostIsDisconnected() {
        startAdvertising()
        controller.onHostConnected(tv)
        controller.onHostConnected(other)
        assertEquals(listOf(other), port.disconnects)
        assertEquals(TransportState.Connecting(port.hostDevice(tv)), controller.state.value)
    }

    /** A subscription from a host that is not connected is ignored. */
    @Test
    fun subscriptionFromUnknownHostIsIgnored() {
        startAdvertising()
        controller.onHostSubscribed(tv)
        assertNull(controller.connectedHost)
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /**
     * A bonded host that connects before the services are added (seen on hardware) is kept
     * through Registering and shown as Resyncing once the services exist; advertising starts
     * only when it leaves.
     */
    @Test
    fun hostConnectingBeforeServicesAddedIsKept() {
        port.bonded += tv
        controller.start()
        port.fireTimers()
        controller.onHostConnected(tv)
        assertEquals(TransportState.Registering, controller.state.value)

        controller.onServicesAdded()
        assertEquals(TransportState.Resyncing(port.hostDevice(tv)), controller.state.value)
        assertEquals(0, port.startAdvertisingCalls)

        controller.onHostDisconnected(tv)
        assertEquals(1, port.startAdvertisingCalls)
    }

    /** An early host that leaves before the services are added is forgotten, and advertising starts. */
    @Test
    fun earlyHostLeavingBeforeServicesAdded() {
        controller.start()
        port.fireTimers()
        controller.onHostConnected(tv)
        controller.onHostDisconnected(tv)
        assertNull(controller.connectedHost)
        controller.onServicesAdded()
        assertEquals(1, port.startAdvertisingCalls)
    }

    /** disconnect() drops the current host and is false without one. */
    @Test
    fun disconnectDropsCurrentHost() {
        startAdvertising()
        assertFalse(controller.disconnect())
        controller.onHostConnected(tv)
        assertTrue(controller.disconnect())
        assertEquals(listOf(tv), port.disconnects)
    }

    /** stop() disconnects, stops advertising, closes the server, and is idempotent. */
    @Test
    fun stopReleasesEverything() {
        startAdvertising()
        controller.onHostConnected(tv)
        controller.stop()
        assertEquals(listOf(tv), port.disconnects)
        assertEquals(1, port.closeServerCalls)
        assertEquals(TransportState.Stopped, controller.state.value)
        assertNull(controller.connectedHost)

        controller.stop()
        assertEquals(1, port.closeServerCalls)
    }

    /** stop() stops advertising only if it was on. */
    @Test
    fun stopStopsAdvertisingOnlyWhenOn() {
        controller.start()
        port.fireTimers()
        controller.onServicesAdded()
        controller.stop()
        assertEquals(0, port.stopAdvertisingCalls)

        startAdvertising()
        controller.stop()
        assertEquals(1, port.stopAdvertisingCalls)
    }

    /** Events from a server that arrive after stop() are ignored. */
    @Test
    fun lateEventsAfterStopAreIgnored() {
        controller.start()
        port.fireTimers()
        controller.stop()
        controller.onServicesAdded()
        controller.onAdvertisingStarted()
        controller.onHostConnected(tv)
        assertEquals(TransportState.Stopped, controller.state.value)
        assertEquals(0, port.startAdvertisingCalls)
        assertNull(controller.connectedHost)
    }

    /** Bluetooth off closes the server; on again starts over with a new server. */
    @Test
    fun bluetoothOffAndOnStartsOver() {
        startAdvertising()
        controller.onHostConnected(tv)
        controller.onHostSubscribed(tv)

        port.bluetoothOn = false
        controller.onBluetoothOff()
        assertEquals(1, port.closeServerCalls)
        assertEquals(TransportState.BluetoothOff, controller.state.value)
        assertNull(controller.connectedHost)

        // Late events from the closed server change nothing.
        controller.onHostDisconnected(tv)
        controller.onAdvertisingStarted()
        assertEquals(TransportState.BluetoothOff, controller.state.value)

        port.bluetoothOn = true
        controller.onBluetoothOn()
        assertEquals(2, port.openServerCalls)
        assertEquals(TransportState.Registering, controller.state.value)
    }

    /** Bluetooth off while stopped is remembered for nothing: the state stays Stopped. */
    @Test
    fun bluetoothOffWhileStoppedStaysStopped() {
        controller.onBluetoothOff()
        assertEquals(TransportState.Stopped, controller.state.value)
        controller.onBluetoothOn()
        assertEquals(0, port.openServerCalls)
    }

    /** A host connection reported while Bluetooth is off is ignored. */
    @Test
    fun hostConnectedWhileBluetoothOffIsIgnored() {
        startAdvertising()
        port.bluetoothOn = false
        controller.onHostConnected(tv)
        assertNull(controller.connectedHost)
        assertEquals(0, port.stopAdvertisingCalls)
    }

    /** Retry after a connect that never completed: start() keeps the server and advertising. */
    @Test
    fun startWhileReadyChangesNothing() {
        startAdvertising()
        controller.start()
        port.fireTimers()
        assertEquals(1, port.openServerCalls)
        assertEquals(1, port.startAdvertisingCalls)
        assertEquals(TransportState.Ready, controller.state.value)
    }

    /** A fake [BlePeripheralPort] that records calls and lets tests set the preconditions. */
    private class FakeBlePort : BlePeripheralPort {
        override var hasAdapter = true
        var hasPermission = true
        var bluetoothOn = true
        var openServerResult = true
        val bonded = mutableSetOf<String>()

        var openServerCalls = 0
        var closeServerCalls = 0
        var startAdvertisingCalls = 0
        var stopAdvertisingCalls = 0
        val disconnects = mutableListOf<String>()

        override fun hasPermission() = hasPermission
        override fun isBluetoothOn() = bluetoothOn
        override fun openServer(): Boolean { openServerCalls++; return openServerResult }
        var addServicesCalls = 0
        override fun addServices(): Boolean { addServicesCalls++; return true }
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        override fun after(delayMillis: Long, action: () -> Unit) { timers += delayMillis to action }
        fun fireTimers() { val due = timers.toList(); timers.clear(); due.forEach { it.second() } }
        override fun closeServer() { closeServerCalls++ }
        override fun startAdvertising(): Boolean { startAdvertisingCalls++; return true }
        override fun stopAdvertising() { stopAdvertisingCalls++ }
        override fun disconnect(address: String) { disconnects += address }
        override fun isBonded(address: String) = address in bonded
        override fun hostDevice(address: String) = HostDevice(address, "Apple TV")

        var savedLayout: List<Int>? = null
        val mismatches = mutableListOf<Pair<List<Int>, List<Int>>>()
        override fun pairedLayout() = savedLayout
        override fun savePairedLayout(handles: List<Int>) { savedLayout = handles }
        override fun noteLayoutMismatch(expected: List<Int>, actual: List<Int>) {
            mismatches += expected to actual
        }
        val droppedLinks = mutableListOf<String>()
        override fun dropLink(address: String) { droppedLinks += address }

    }
}
