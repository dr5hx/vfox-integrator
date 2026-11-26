package com.dr5hx.vfox.inject.go

import com.dr5hx.vfox.inject.VfoxPathUtil
import com.dr5hx.vfox.inject.VfoxProjectInitializer
import com.dr5hx.vfox.inject.VfoxProjectSdks
import com.dr5hx.vfox.settings.VfoxSettings
import com.goide.sdk.GoSdkService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Points the *project's* Go SDK at the Go version the project declares in vfox.
 *
 * Go does not use the platform's project-SDK slot; the Go plugin keeps a per-project selection in
 * [GoSdkService]. Without this a project whose `.vfox.toml` says `golang = "1.27.1"` keeps whatever
 * GOROOT was chosen before, even though the right one is registered and selectable.
 *
 * Runs as a [VfoxProjectInitializer] so it is guaranteed to run after the scan registered the SDK.
 * Nothing is written when the project declares no Go, or when it already points at the same GOROOT.
 *
 * Lives in `vfox-go.xml`, merged only where the Go plugin is present.
 */
class VfoxGoProjectSdk : VfoxProjectInitializer {

    override fun apply(project: Project) {
        if (!VfoxSettings.getInstance().applyProjectSdkDefaults) return
        val basePath = project.basePath ?: return

        val home = try {
            VfoxProjectSdks.declaredHome(basePath, GO_SDK)
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not resolve the project's Go SDK", e)
            return
        } ?: return

        val service = GoSdkService.getInstance(project)
        // Already right: compared on the real path, because a project link and the GOROOT it points
        // at are one directory under two names, and rewriting an already-correct project is noise.
        val current = service.getSdk(null)?.homePath
        if (current != null && VfoxPathUtil.sameRealPath(current, home)) return

        try {
            ApplicationManager.getApplication().runWriteAction {
                // setSdkHomePath writes the project-level GOROOT directly, which is what the
                // Settings → Go → GOROOT field reads; constructing a GoSdk would go through the
                // same path with more to go wrong.
                service.setSdkHomePath(home)
            }
            thisLogger().info("vfox: set the project's Go SDK to $home")
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not set the project's Go SDK", e)
        }
    }

    private companion object {
        /** The vfox plugin name for Go. */
        const val GO_SDK = "golang"
    }
}
