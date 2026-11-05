package org.ghostsinthelab.app.ringomote.remote

import kotlinx.coroutines.test.runTest
import org.ghostsinthelab.app.ringomote.hid.HidReport
import org.ghostsinthelab.app.ringomote.hid.HidReportSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the Wi-Fi-first routing rule of [RemoteRouter] and the per-link release rule, with
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

    /** Wi-Fi wins while its session is open, even with the Bluetooth host subscribed. */
    @Test
    fun wifiFirst() = runTest {
        bluetooth = true; wifi = true
        assertEquals(RemoteRouter.Link.WiFi, router.activeLink())
        router.tap(RemoteControl.Up)
        assertEquals(listOf("down Up", "up Up"), companion.events)
        assertTrue(sink.reports.isEmpty())
    }

    /** Without Wi-Fi the press goes over Bluetooth as a HID report pair. */
    @Test
    fun bluetoothWhenWifiIsNotConnected() = runTest {
        bluetooth = true
        assertEquals(RemoteRouter.Link.Bluetooth, router.activeLink())
        router.tap(RemoteControl.Select)
        assertEquals(2, sink.reports.size)
        assertTrue(companion.events.isEmpty())
    }

    /** The press goes over Wi-Fi as down/up. */
    @Test
    fun wifiSendsDownAndUp() = runTest {
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

    /** A control pressed on Bluetooth is released on Bluetooth even if Wi-Fi came up meanwhile. */
    @Test
    fun releasedOnTheLinkItWasPressedOn() = runTest {
        bluetooth = true
        router.press(RemoteControl.Menu)
        wifi = true
        router.release(RemoteControl.Menu)
        assertEquals(2, sink.reports.size)
        assertTrue(companion.events.isEmpty())
    }

    /** Mute has no Wi-Fi command: dropped on Wi-Fi alone, sent over Bluetooth when that is up too. */
    @Test
    fun muteFallsBackToBluetooth() = runTest {
        wifi = true
        assertFalse(router.supports(RemoteControl.Mute))
        assertFalse(router.press(RemoteControl.Mute))
        bluetooth = true
        assertEquals(RemoteRouter.Link.WiFi, router.activeLink())
        assertTrue(router.supports(RemoteControl.Mute))
        assertTrue(router.press(RemoteControl.Mute))
        assertTrue(companion.events.isEmpty())
        assertEquals(1, sink.reports.size)
    }

    /** releaseAll lifts both links and forgets what was held. */
    @Test
    fun releaseAllCoversBothLinks() = runTest {
        wifi = true
        router.press(RemoteControl.Up)
        router.releaseAll()
        assertEquals(listOf("down Up", "all up"), companion.events)
        assertEquals(2, sink.reports.size)
        wifi = false; bluetooth = true
        router.press(RemoteControl.Up)
        assertEquals(3, sink.reports.size)
    }
}
