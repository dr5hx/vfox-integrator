package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdk
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * Enumerates every SDK version vfox has installed on this machine.
 *
 * Two sources are combined, CLI first and disk second, because each alone is incomplete:
 *
 *  * `vfox info <sdk>@<version> --json` is authoritative — it yields the real home directory and
 *    the exact environment variables vfox would export (`JAVA_HOME`, `GOROOT`, ...). But it needs
 *    the vfox executable, and costs one process per version.
 *  * Scanning the `v-<version>` directories needs nothing but the filesystem, so SDKs stay visible
 *    even when vfox is not on `PATH` (or when it is present but the CLI has moved on).
 *
 * The version list always comes from disk (fast, and matches what `vfox list` reports); the CLI is
 * then consulted per version to enrich it with home and environment details.
 *
 * The directory shape is *not* uniform, which is the one thing to know before touching this: most
 * SDKs live under `<storage>/cache/<sdk>/v-<version>/`, but some (golang on a typical Windows
 * install) sit directly at `<storage>/<sdk>/v-<version>/`. [enumerateSdks] therefore consults both
 * locations for every SDK name rather than assuming a single root.
 */
class VfoxScanner(
    private val locator: VfoxHomeLocator = VfoxHomeLocator(),
    private val runner: VfoxCommandRunner = VfoxCliRunner(),
) {

    fun scan(): VfoxScan {
        val home = locator.locateHome()
            ?: return VfoxScan(error = "vfox home directory not found")

        val storageRoot = locator.locateSdkRoot(home)
        val sdks = enumerateSdks(storageRoot, locator.locatePluginRoot(home))

        if (sdks.isEmpty()) {
            return VfoxScan(
                home = home.absolutePath,
                sdkRoot = storageRoot.absolutePath,
                error = "No installed SDKs found under ${storageRoot.absolutePath}",
            )
        }

        val activeVersions = detectActiveVersions()
        val result = sdks.map { sdk ->
            sdk.copy(versions = sdk.versions.map { version ->
                version.copy(active = activeVersions[sdk.name] == version.version)
            })
        }
        return VfoxScan(home = home.absolutePath, sdkRoot = storageRoot.absolutePath, sdks = result)
    }

    /**
     * Discovers SDK names and their installed versions from disk.
     *
     * SDK names come from the plugin directory (which lists every SDK vfox knows about) unioned
     * with the SDK directories that actually exist, so an SDK remains visible even if its plugin was
     * removed.
     */
    private fun enumerateSdks(storageRoot: File, pluginRoot: File): List<VfoxSdk> {
        val names = LinkedHashSet<String>()
        pluginRoot.listFiles()?.filter { it.isDirectory }?.forEach { names += it.name }
        // Both layout shapes contribute names: `<storage>/cache/<sdk>` and `<storage>/<sdk>`.
        for (dir in listOf(File(storageRoot, VfoxHomeLocator.CACHE_DIR), storageRoot)) {
            dir.listFiles()?.filter { it.isDirectory }?.forEach { names += it.name }
        }

        return names.sorted().mapNotNull { name ->
            val versions = installedVersions(storageRoot, name)
            if (versions.isEmpty()) null else VfoxSdk(name, versions)
        }
    }

    /**
     * Reads installed versions for [sdk] from every candidate directory.
     *
     * vfox splits its SDKs across layouts — most under `<storage>/cache/<sdk>/v-<version>/`, some
     * (golang on this machine) directly under `<storage>/<sdk>/v-<version>/`. Both are consulted and
     * the results merged, so no SDK is missed for living in the "other" place.
     */
    private fun installedVersions(storageRoot: File, sdk: String): List<VfoxSdkVersion> {
        val seen = LinkedHashSet<String>()
        val versions = ArrayList<VfoxSdkVersion>()

        for (sdkDir in locator.sdkRootCandidates(storageRoot, sdk)) {
            if (!sdkDir.isDirectory) continue
            val names = sdkDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(VERSION_PREFIX) }
                ?.map { it.name.removePrefix(VERSION_PREFIX) }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            for (version in names) {
                if (seen.add(version)) versions += enrich(sdk, version, sdkDir)
            }
        }
        return versions.sortedWith(VERSION_ORDER_BY_VERSION)
    }

    /**
     * Enriches a version with its home and environment via `vfox info --json`, falling back to the
     * on-disk layout when the CLI is unavailable or the version is not resolvable.
     */
    private fun enrich(sdk: String, version: String, sdkDir: File): VfoxSdkVersion {
        val json = runner.run(listOf("info", "$sdk@$version", "--json"), workingDirectory = null)
        if (json != null) {
            val parsed = parseInfo(sdk, version, json)
            if (parsed != null) return parsed
        }
        return VfoxSdkVersion(sdk = sdk, version = version, home = diskHome(sdkDir, sdk, version))
    }

    /**
     * The on-disk home for a version, e.g. `<sdkDir>/v-24.14.0/nodejs-24.14.0`.
     *
     * Falls back to the version directory itself for layouts that do not nest a named subdirectory.
     */
    private fun diskHome(sdkDir: File, sdk: String, version: String): String? {
        val versionDir = File(sdkDir, "$VERSION_PREFIX$version")
        if (!versionDir.isDirectory) return null
        val nested = versionDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("$sdk-") }
        return (nested ?: versionDir).absolutePath
    }

    /**
     * Maps each SDK to the version vfox currently activates, read from `vfox current`.
     *
     * `vfox current` resolves against the working directory, so running it in the vfox home itself
     * reports the globally active versions rather than a project's.
     */
    private fun detectActiveVersions(): Map<String, String> {
        val result = HashMap<String, String>()
        val json = runner.run(listOf("current"), workingDirectory = null)
        if (json != null) {
            // `vfox current` prints lines like "nodejs -> 24.14.0"; N/A means no active version.
            for (line in json.lineSequence()) {
                val parts = line.split("->").map { it.trim() }
                if (parts.size != 2) continue
                val (name, version) = parts
                if (version.isNotEmpty() && version != "N/A") result[name] = version
            }
        }
        return result
    }

    companion object {
        private const val VERSION_PREFIX = "v-"

        /** Orders discovered versions newest-first by their version string. */
        private val VERSION_ORDER_BY_VERSION = Comparator<VfoxSdkVersion> { a, b ->
            compareVersions(b.version, a.version)
        }

        /** Compares two vfox version strings segment by segment, numerically where possible. */
        fun compareVersions(a: String, b: String): Int {
            val aParts = a.split('.', '-', '+')
            val bParts = b.split('.', '-', '+')
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val x = aParts.getOrNull(i) ?: ""
                val y = bParts.getOrNull(i) ?: ""
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val cmp = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> 1
                    yn != null -> -1
                    else -> x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }

        /**
         * Parses `vfox info <sdk>@<version> --json`.
         *
         * Returns null when the document is not the expected shape (vfox prints a plain-text error
         * to stdout for unknown versions).
         */
        fun parseInfo(sdk: String, version: String, json: String): VfoxSdkVersion? {
            val root = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
            val home = Json.stringAt(root, "home") ?: return null
            val env = Json.objectAt(root, "env")
            return VfoxSdkVersion(
                sdk = sdk,
                version = Json.stringAt(root, "version") ?: version,
                home = home,
                paths = Json.stringListAt(env, "paths"),
                variables = Json.stringMapAt(env, "variables"),
            )
        }
    }
}
