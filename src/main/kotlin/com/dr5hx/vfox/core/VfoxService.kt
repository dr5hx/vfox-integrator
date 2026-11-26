package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicReference

/**
 * Notified when a scan finishes.
 *
 * The tool window is built around the project opening, while the start-up scan runs in the
 * background — so the scan can complete before or after the window exists, and looking once cannot
 * tell which. Publishing every completed scan lets each view refresh itself, instead of sitting on
 * "not scanned yet" until the user rescans by hand.
 */
fun interface VfoxScanListener {
    /** Called after a scan finished, with its result. */
    fun scanCompleted(scan: VfoxScan)
}

/**
 * Application-level holder for the latest vfox scan.
 *
 * A scan is expensive (one vfox process per installed version), so it runs once in the background
 * at start-up and on explicit refresh; everything else reads the cached result.
 */
interface VfoxService {
    /** The most recent scan result, or null when no scan has completed yet. */
    fun lastScan(): VfoxScan?

    /** Performs a scan, caches it, and returns the result. Safe to call from a background thread. */
    fun rescan(): VfoxScan

    /** Every version discovered in the last scan. */
    fun versions(): List<VfoxSdkVersion>

    /** The vfox executable in use, for diagnostics; null when running from disk scan only. */
    fun executablePath(): String?

    companion object {
        /**
         * Published after every completed scan, whichever path ran it — the start-up activity, the
         * tool window's *Rescan*, or a settings change.
         *
         * Delivered on the thread that ran the scan, so a listener that touches the UI must hop to
         * the EDT itself.
         */
        val SCAN_COMPLETED: Topic<VfoxScanListener> =
            Topic.create("vfox scan completed", VfoxScanListener::class.java)

        fun getInstance(): VfoxService = service()
    }
}

@Service
class VfoxServiceImpl : VfoxService {

    private val cached = AtomicReference<VfoxScan?>(null)
    private val lastExecutable = AtomicReference<String?>(null)

    override fun lastScan(): VfoxScan? = cached.get()

    override fun versions(): List<VfoxSdkVersion> = cached.get()?.allVersions() ?: emptyList()

    override fun executablePath(): String? = lastExecutable.get()

    override fun rescan(): VfoxScan {
        val scan = try {
            buildScanner().scan()
        } catch (e: Exception) {
            thisLogger().error("vfox scan failed", e)
            VfoxScan(error = e.message ?: e.javaClass.simpleName)
        }
        record(scan)
        return scan
    }

    /**
     * Caches [scan] and tells every listener about it.
     *
     * Separated from [rescan] so the state change a completed scan makes can be exercised without a
     * vfox installation — the scan itself being the only part that needs one.
     */
    internal fun record(scan: VfoxScan) {
        // The cache is written *before* listeners run, so a listener that reads [lastScan] in its
        // handler sees the scan it was just handed rather than the previous one.
        cached.set(scan)
        thisLogger().info(
            "vfox scan: ${scan.sdks.size} SDK(s), ${scan.allVersions().size} version(s), " +
                "home=${scan.home}, cli=${lastExecutable.get()}" +
                (scan.error?.let { ", error=$it" } ?: "")
        )
        // Every scan is recorded here, from whichever path triggered it (start-up, tool-window
        // rescan, settings change), so the log always reflects what the plugin last saw.
        VfoxLog.scan(scan)
        ApplicationManager.getApplication().messageBus
            .syncPublisher(VfoxService.SCAN_COMPLETED)
            .scanCompleted(scan)
    }

    /**
     * Builds a scanner honouring the user's configured vfox executable.
     *
     * The executable is resolved from the configured path first, then `PATH`; when none is found the
     * runner reports "unavailable" and the scan falls back to reading the SDK directories directly.
     */
    private fun buildScanner(): VfoxScanner {
        val settings = VfoxSettings.getInstance()
        val executable = VfoxCliRunner.locateExecutable(settings.state.vfoxExecutable)
        lastExecutable.set(executable?.absolutePath)

        val locator = VfoxHomeLocator()
        val runner = VfoxCliRunner(executable)
        return VfoxScanner(locator, runner)
    }
}
