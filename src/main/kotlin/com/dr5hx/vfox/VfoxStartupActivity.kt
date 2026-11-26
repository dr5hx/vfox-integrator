package com.dr5hx.vfox

import com.dr5hx.vfox.core.VfoxLog
import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.inject.SdkInjectorRegistry
import com.dr5hx.vfox.inject.SdkInjectors
import com.dr5hx.vfox.inject.VfoxProjectInitializer
import com.dr5hx.vfox.settings.VfoxSettings
import com.dr5hx.vfox.ui.VfoxNotifier
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Scans vfox once at IDE start-up and registers the discovered SDKs.
 *
 * Runs as a [ProjectActivity] so it executes off the EDT and can be cancelled; it is deliberately
 * cheap when there is nothing to do (no vfox, or no enabled integrations).
 *
 * Registration is followed by the [VfoxProjectInitializer]s — the per-project integrations that have
 * to act *after* the SDKs exist (pointing the project's Node.js interpreter at the version it
 * declares, for instance). They run here rather than as their own start-up activities so they cannot
 * race the registration they depend on.
 */
class VfoxStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = VfoxSettings.getInstance()
        val service = VfoxService.getInstance()

        // The scan always runs. The registration toggles decide only what is written into the IDE's
        // SDK tables — not whether vfox is looked at. Skipping the scan when every kind is turned off
        // would leave the tool window permanently empty and, worse, silently starve the build-tool
        // integrations (terminal `PATH`, Gradle's JVM, Maven's home), which read the scanned versions
        // rather than registering SDKs of their own.
        val scan = service.rescan()
        if (scan.isEmpty) {
            scan.error?.let {
                thisLogger().info("vfox: nothing to register ($it)")
                VfoxNotifier.reportScan(scan, project = project)
            }
            runProjectInitializers(project)
            return
        }

        val offered = SdkInjectors.registrablePlugins()
        if (settings.allRegistrationsDisabled(offered)) {
            thisLogger().debug("vfox: all SDK registrations disabled; scanned but registering nothing")
            runProjectInitializers(project)
            return
        }

        val counts = SdkInjectorRegistry.registerAll(
            versions = scan.allVersions(),
            isEnabled = settings::isRegistrationEnabled,
        )
        VfoxLog.registration(counts)
        thisLogger().info(
            "vfox: registered ${counts[InjectResult.ADDED] ?: 0} new SDK(s), " +
                "${counts[InjectResult.ALREADY_PRESENT] ?: 0} already present"
        )
        VfoxNotifier.reportScan(scan, registered = counts, project = project)
        runProjectInitializers(project)
    }

    /**
     * Runs each contributed [VfoxProjectInitializer], isolating its failures.
     *
     * One initializer failing must not stop the others, and none may break start-up: they touch
     * bundled-plugin internals with no compatibility guarantee, so a failure is logged and skipped.
     */
    private fun runProjectInitializers(project: Project) {
        for (initializer in VfoxProjectInitializer.all()) {
            try {
                initializer.apply(project)
            } catch (e: Exception) {
                thisLogger().debug("vfox: project initializer ${initializer.javaClass.name} failed", e)
            }
        }
    }
}
