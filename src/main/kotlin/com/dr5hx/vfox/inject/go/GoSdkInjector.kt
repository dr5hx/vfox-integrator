package com.dr5hx.vfox.inject.go

import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.inject.SdkInjector
import com.dr5hx.vfox.inject.VfoxPathUtil
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.goide.sdk.GoSdk
import com.goide.sdk.combobox.GoSdkList
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * Registers vfox Go installations as Go SDKs.
 *
 * Go is the odd one out: it does not use the platform's `ProjectJdkTable`/`SdkType` model at all.
 * The Go plugin keeps its own list of SDK roots in [GoSdkList], an application-level
 * `PersistentStateComponent` whose entries are `file://` URLs. `GOROOT` is not stored separately —
 * it *is* the SDK home, i.e. the directory containing `bin/go`.
 *
 * Lives in `vfox-go.xml`, merged only where the Go plugin is present.
 */
class GoSdkInjector : SdkInjector {

    override val sdkName: String = "golang"

    override fun register(version: VfoxSdkVersion): InjectResult {
        val home = version.home?.let { File(it) } ?: run {
            thisLogger().debug("Skipping ${version.displayName}: home not resolvable")
            return InjectResult.SKIPPED
        }
        if (!File(home, "bin").isDirectory) {
            thisLogger().debug("Skipping ${version.displayName}: no bin directory under $home")
            return InjectResult.SKIPPED
        }

        val list = GoSdkList.getInstance()
        val url = toFileUrl(home.absolutePath)
        // Compare on the real directory, so a project's `.vfox/sdks/golang` link and the store it
        // points at are recognised as one SDK rather than added twice under two paths.
        val alreadyKnown = list.allGoSdks.any { VfoxPathUtil.sameRealPath(fileUrlToPath(it.homeUrl), home.absolutePath) }
        if (alreadyKnown) return InjectResult.ALREADY_PRESENT

        ApplicationManager.getApplication().runWriteAction {
            list.addSdk(url)
        }
        return InjectResult.ADDED
    }

    /**
     * Converts a filesystem path to the `file://` URL form [GoSdkList] stores.
     *
     * The Go plugin normalizes these itself, but writing the canonical shape keeps the on-disk
     * `go.sdk.xml` identical to what the IDE produces, so entries written here and by hand compare
     * equal.
     */
    private fun toFileUrl(path: String): String = "file://" + path.replace('\\', '/')

    /** The filesystem path behind a `file://` URL [GoSdkList] holds, for comparing the real file. */
    private fun fileUrlToPath(url: String?): String? =
        url?.removePrefix("file://")?.takeIf { it.isNotBlank() }
}
