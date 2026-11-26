package com.dr5hx.vfox.inject

import com.dr5hx.vfox.settings.VfoxSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager

/**
 * Points the *project's* project-SDK slot at the SDK the project declares in vfox.
 *
 * The project SDK is the one the IDE, the build tools and the module compiler read as "this
 * project's SDK" — for a JVM project that is its JDK. It defaults to whatever was chosen before, so
 * a project whose `.vfox.toml` says `java = "11"` still builds against another JDK even though the
 * right one is registered and selectable. This makes the default correct instead of merely
 * available.
 *
 * Lives in the base plugin because it uses only platform APIs: the SDK is looked up through
 * [VfoxProjectSdks], which resolves its `SdkType` by class name and returns null where this product
 * has no such kind, and the write is [ProjectRootManager.setProjectSdk]. Nothing is written when the
 * project declares no SDK this product knows, or when the project already has it.
 *
 * Gated behind the *Set each project's IDE SDKs* setting (off by default), since it is one of the
 * integrations that writes a project's own configuration rather than only offering candidates.
 */
class VfoxProjectSdkInitializer : VfoxProjectInitializer {

    override fun apply(project: Project) {
        if (!VfoxSettings.getInstance().applyProjectSdkDefaults) return
        val basePath = project.basePath ?: return

        val wanted = try {
            VfoxProjectSdks.projectSdkFor(basePath)
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not resolve the project's SDK", e)
            return
        } ?: return

        val rootManager = ProjectRootManager.getInstance(project)
        // Already right: compared on the home path rather than the name, because a project may point
        // at the same SDK under a different name (hand-added, or renamed), and rewriting it on every
        // start-up would be noise. Links are resolved, so the project's `.vfox/sdks` link and the
        // store directory behind it count as the same home.
        val current = rootManager.projectSdk?.homePath
        if (current != null && VfoxPathUtil.sameRealPath(current, wanted.homePath)) return

        try {
            ApplicationManager.getApplication().runWriteAction {
                rootManager.setProjectSdk(wanted)
            }
            thisLogger().info("vfox: set the project SDK to ${wanted.name}")
        } catch (e: Exception) {
            thisLogger().debug("vfox: could not set the project SDK", e)
        }
    }
}
