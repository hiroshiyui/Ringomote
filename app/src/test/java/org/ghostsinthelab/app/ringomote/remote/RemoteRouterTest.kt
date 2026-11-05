package org.ghostsinthelab.app.ringomote.remote

import kotlinx.coroutines.test.runTest
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the Bluetooth-first routing rule of [RemoteRouter] and the per-link release rule, with
 * a recording HID sink behind a real [RemoteSession] and a recording [CompanionInput].
 */
class RemoteRouterTest {
    private val sink = object : HidReportSink {
        val reports = mutableListOf<HidReport>()
        override fun send(report: HidReport): Boolean { reports += report; return true }
    }
    private val session = RemoteSession(sink)
    private val companion = object : CompanionInput {
        val events = mutableListOf<String>()
        override fun supports(control: RemoteControl) = control != RemoteControl.Mute
        override suspend fun press(control: RemoteControl): Boolean { events += "down $control"; return true }
        override suspend fun release(control: RemoteControl): Boolean { events += "up $control"; return true }
        override suspend fun releaseAll() { events += "all up" }
    }
    private var bluetooth = false
    private var wifi = false
    private val router = RemoteRouter(session, companion, { bluetooth }, { wifi })

    /** Bluetooth wins while it is connected, even with the Wi-Fi session open. */
    @Test
    fun bluetoothFirst() = runTest {
        bluetooth = true; wifi = true
        assertEquals(RemoteRouter.Link.Bluetooth, router.activeLink())
        router.tap(RemoteControl.Up)
        assertEquals(2, sink.reports.size)
        assertTrue(companion.events.isEmpty())
    }

    /** Without Bluetooth the press goes over Wi-Fi as down/up. */
    @Test
    fun wifiWhenBluetoothIsNotConnected() = runTest {
        wifi = true
        assertEquals(RemoteRouter.Link.WiFi, router.activeLink())
        router.tap(RemoteControl.Select)
        assertEquals(listOf("down Select", "up Select"), companion.events)
        assertTrue(sink.reports.isEmpty())
    }

    /** Neither link: the press is dropped and reported as such. */
    @Test
    fun nothingWhenNoLink() = runTest {
        assertFalse(router.press(RemoteControl.Up))
        assertTrue(sink.reports.isEmpty() && companion.events.isEmpty())
    }

    /** A control pressed on Wi-Fi is released on Wi-Fi even if Bluetooth came up meanwhile. */
    @Test
    fun releasedOnTheLinkItWasPressedOn() = runTest {
        wifi = true
        router.press(RemoteControl.Menu)
        bluetooth = true
        router.release(RemoteControl.Menu)
        assertEquals(listOf("down Menu", "up Menu"), companion.events)
        assertTrue(sink.reports.isEmpty())
    }

    /** Mute has no Wi-Fi command: unsupported there, supported over Bluetooth. */
    @Test
    fun muteOnlyOverBluetooth() = runTest {
        wifi = true
        assertFalse(router.supports(RemoteControl.Mute))
        assertFalse(router.press(RemoteControl.Mute))
        bluetooth = true
        assertTrue(router.supports(RemoteControl.Mute))
        assertTrue(router.press(RemoteControl.Mute))
    }

    /** releaseAll lifts both links and forgets what was held. */
    @Test
    fun releaseAllCoversBothLinks() = runTest {
        wifi = true
        router.press(RemoteControl.Up)
        router.releaseAll()
        assertEquals(listOf("down Up", "all up"), companion.events)
        assertEquals(2, sink.reports.size)
        bluetooth = true
        router.press(RemoteControl.Up)
        assertEquals(3, sink.reports.size)
    }
}
