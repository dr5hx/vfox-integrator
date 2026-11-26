package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import java.io.File

/**
 * Shared helpers for registering vfox versions as SDKs in the platform-wide [ProjectJdkTable].
 *
 * The table holds SDKs of every kind (not just JDKs, despite the name). Writes require a write
 * action, and registration must be idempotent across repeated scans.
 */
object SdkTableHelper {

    /** The home directory for [version], or null when it is unknown or does not exist on disk. */
    fun resolveHome(version: VfoxSdkVersion): String? {
        val home = version.home ?: return null
        return if (File(home).isDirectory) home else null
    }

    /**
     * Finds an existing SDK of [type] already pointing at [home].
     *
     * Matching on the home path — rather than on the name — keeps the table stable even if the user
     * renamed an SDK, and means a scan will not add a second entry for a path already configured
     * by hand. Homes are compared with links resolved, so a project's `.vfox/sdks/nodejs` and the
     * store directory it points at are recognised as the same SDK rather than registered twice.
     */
    fun findByHome(type: SdkTypeId, home: String): Sdk? {
        val canonical = VfoxPathUtil.canonical(home)
        return ProjectJdkTable.getInstance().getSdksOfType(type)
            .firstOrNull { sdk ->
                sdk.homePath?.let { VfoxPathUtil.canonical(it) == canonical } == true
            }
    }

    /**
     * Adds [sdk] if no SDK of its type already points at the same home.
     *
     * @return [InjectResult.ADDED] when the SDK was added, [InjectResult.ALREADY_PRESENT] otherwise.
     */
    fun addIfAbsent(sdk: Sdk): InjectResult {
        val existing = findByHome(sdk.sdkType, sdk.homePath ?: return InjectResult.FAILED)
        if (existing != null) return InjectResult.ALREADY_PRESENT

        ApplicationManager.getApplication().runWriteAction {
            ProjectJdkTable.getInstance().addJdk(sdk)
        }
        return InjectResult.ADDED
    }

    /** Creates an SDK of [type] named for [version], with its home and version string set. */
    fun createSdk(type: SdkTypeId, name: String, home: String, versionString: String?): Sdk {
        return WriteAction.compute<Sdk, Exception> {
            val sdk = ProjectJdkTable.getInstance().createSdk(name, type)
            sdk.sdkModificator.apply {
                this.homePath = home
                if (versionString != null) this.versionString = versionString
            }.commitChanges()
            sdk
        }
    }

    /** Normalizes a path for comparison: separators unified and case-folded on case-insensitive OSes. */
    private fun normalize(path: String): String = VfoxPathUtil.normalize(path)
}

/** Small path helpers shared by the injectors. */
object VfoxPathUtil {

    /** Whether the default filesystem is case-insensitive (Windows, macOS). */
    val isCaseInsensitiveFs: Boolean by lazy {
        val os = System.getProperty("os.name").lowercase()
        os.contains("win") || os.contains("mac")
    }

    /** Normalizes a path for comparison: separators unified and case-folded on case-insensitive OSes. */
    fun normalize(path: String): String {
        val unified = path.replace('\\', '/').trimEnd('/')
        return if (isCaseInsensitiveFs) unified.lowercase() else unified
    }

    /**
     * The real path of [path]: links (junctions, symlinks) resolved and `.`/`..` collapsed, with
     * separators unified and case folded.
     *
     * This is what makes "the same file" detectable: a project's `.vfox/sdks/nodejs/node.exe` and
     * the store copy it points at are one file under two names, and only canonicalization says so.
     * A path that cannot be canonicalized (an invalid one, or a transient IO failure) falls back to
     * its literal normalized form rather than throwing, so a comparison always has an answer.
     */
    fun canonical(path: String): String =
        runCatching { File(path).canonicalFile.path }.getOrElse { path }.let { normalize(it) }

    /**
     * Whether [a] and [b] name the same file on disk.
     *
     * Compared through [canonical], so a link and its target compare equal while two distinct
     * directories never do. Two paths that do not exist still compare equal to each other when they
     * are spelled the same, so a dangling entry is not mistaken for a duplicate of something else.
     */
    fun sameRealPath(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return canonical(a) == canonical(b)
    }
}
