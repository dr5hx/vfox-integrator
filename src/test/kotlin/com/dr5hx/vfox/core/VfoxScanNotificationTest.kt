package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdk
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Guards the hand-off between a finished scan and the views that show it.
 *
 * The start-up scan runs in the background while the tool window is built, so a scan can finish
 * before or after the window exists. Both orders have to end with the window showing the result;
 * the failure this pins down is the one where the window is built first, finds no cached scan yet,
 * and then never hears about the scan that followed — leaving it reading "not scanned yet" until
 * the user rescans by hand.
 *
 * Names are JUnit 3 style (`test...`) because [BasePlatformTestCase] is a `TestCase`: the platform
 * test framework discovers methods by prefix, so the backtick names used elsewhere in this suite
 * would never run here.
 */
class VfoxScanNotificationTest : BasePlatformTestCase() {

    /** The bus the tool window actually subscribes to: the application-level one. */
    private val bus get() = com.intellij.openapi.application.ApplicationManager.getApplication().messageBus

    private fun scanOf(vararg versions: Pair<String, String>) = VfoxScan(
        home = "/home/.vfox",
        sdkRoot = "/store",
        sdks = versions.groupBy({ it.first }, { it.second })
            .map { (sdk, list) -> VfoxSdk(sdk, list.map { VfoxSdkVersion(sdk, it) }) },
    )

    /** Collects the scans a listener is told about. */
    private class Recorder : VfoxScanListener {
        val received = ArrayList<VfoxScan>()
        override fun scanCompleted(scan: VfoxScan) {
            received += scan
        }
    }

    fun testACompletedScanReachesAListener() {
        val recorder = Recorder()
        bus.connect(testRootDisposable).subscribe(VfoxService.SCAN_COMPLETED, recorder)

        val scan = scanOf("nodejs" to "18.20.8")
        (VfoxService.getInstance() as VfoxServiceImpl).record(scan)

        assertEquals(1, recorder.received.size)
        assertEquals("nodejs", recorder.received.single().sdks.single().name)
    }

    fun testTheCachedScanIsAlreadyThePublishedOneWhenAListenerRuns() {
        // A listener that reads the cache while handling the event must see *this* scan, not the
        // previous one: the tool window renders from `lastScan()`, so a cache written after the
        // publish would make it render the scan before the one it was told about.
        val service = VfoxService.getInstance() as VfoxServiceImpl
        service.record(scanOf("java" to "11.0.16"))

        var seenWhileHandling: VfoxScan? = null
        bus.connect(testRootDisposable)
            .subscribe(VfoxService.SCAN_COMPLETED, VfoxScanListener { seenWhileHandling = service.lastScan() })

        val second = scanOf("python" to "3.12.10")
        service.record(second)

        assertSame(second, seenWhileHandling)
        assertSame(second, service.lastScan())
    }

    fun testEveryCompletedScanIsPublishedNotOnlyTheFirst() {
        // A window opened after the first scan still needs the ones that follow (a Rescan, or the
        // start-up scan replacing an earlier empty result), so the topic cannot be one-shot.
        val recorder = Recorder()
        bus.connect(testRootDisposable).subscribe(VfoxService.SCAN_COMPLETED, recorder)

        val service = VfoxService.getInstance() as VfoxServiceImpl
        service.record(scanOf("nodejs" to "18.20.8"))
        service.record(scanOf("nodejs" to "20.20.2"))

        assertEquals(2, recorder.received.size)
        assertEquals(
            listOf("18.20.8", "20.20.2"),
            recorder.received.map { it.sdks.single().versions.single().version },
        )
    }

    fun testAnEmptyScanIsPublishedToo() {
        // "Nothing found" is a result the window has to show — it replaces a stale list and is what
        // the status line and the balloon report. Swallowing it would leave the window showing the
        // previous scan's rows after a rescan found nothing.
        val recorder = Recorder()
        bus.connect(testRootDisposable).subscribe(VfoxService.SCAN_COMPLETED, recorder)

        (VfoxService.getInstance() as VfoxServiceImpl).record(VfoxScan(error = "vfox home directory not found"))

        assertEquals(1, recorder.received.size)
        assertTrue(recorder.received.single().isEmpty)
    }

    fun testADisconnectedListenerStopsHearingScans() {
        // The tool window disconnects when it closes; a later scan must not reach it.
        val recorder = Recorder()
        val connection = bus.connect(testRootDisposable)
        connection.subscribe(VfoxService.SCAN_COMPLETED, recorder)
        connection.disconnect()

        (VfoxService.getInstance() as VfoxServiceImpl).record(scanOf("nodejs" to "18.20.8"))

        assertTrue("a disconnected listener must not be called", recorder.received.isEmpty())
    }
}
