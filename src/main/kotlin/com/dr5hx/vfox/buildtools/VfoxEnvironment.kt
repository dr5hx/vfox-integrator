package com.dr5hx.vfox.buildtools

import com.dr5hx.vfox.core.VfoxService
import com.dr5hx.vfox.model.VfoxSdkVersion
import java.io.File

/**
 * Computes the vfox environment to apply to an external process.
 *
 * Every build-tool integration (terminal, Gradle, Maven, run configurations) needs the same thing:
 * the `PATH` entries and variables vfox would export for the versions a project selects.
 * Centralizing it here keeps one source of truth — the environment vfox itself reports — rather
 * than per-tool guesswork.
 */
object VfoxEnvironment {

    /** A resolved environment: directories to prepend to `PATH`, plus named variables. */
    data class Env(
        val pathEntries: List<String>,
        val variables: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = pathEntries.isEmpty() && variables.isEmpty()

        val isNotEmpty: Boolean get() = !isEmpty

        companion object {
            val EMPTY = Env(emptyList(), emptyMap())
        }
    }

    /**
     * Builds the environment for a set of selected versions.
     *
     * @param required the versions the project asks for, as `sdk to version`.
     * @param allVersions every version from the last scan, used to look up details.
     * @param includeActiveWhenEmpty when nothing is required, fall back to each SDK's active
     *   version — which is what a plain `vfox env` yields in a directory without version files.
     */
    fun resolve(
        required: List<Pair<String, String>>,
        allVersions: List<VfoxSdkVersion>,
        includeActiveWhenEmpty: Boolean,
    ): Env {
        val selected = if (required.isNotEmpty()) {
            required.mapNotNull { (sdk, version) ->
                allVersions.firstOrNull { it.sdk == sdk && it.version == version }
            }
        } else if (includeActiveWhenEmpty) {
            allVersions.filter { it.active }
        } else {
            emptyList()
        }

        val pathEntries = LinkedHashSet<String>()
        val variables = LinkedHashMap<String, String>()
        for (version in selected) {
            // Prefer what vfox reported; fall back to the conventional layout only when the CLI
            // could not enrich the version, so an un-enriched scan still produces a usable PATH.
            val entries = version.paths.ifEmpty { listOfNotNull(defaultPathEntry(version)) }
            pathEntries += entries
            variables.putAll(version.variables)
        }
        return Env(pathEntries.toList(), variables)
    }

    /** Convenience: resolve against the environment discovered by the last scan. */
    fun resolveForProject(
        required: List<Pair<String, String>>,
        includeActiveWhenEmpty: Boolean = true,
    ): Env = resolve(
        required = required,
        allVersions = VfoxService.getInstance().versions(),
        includeActiveWhenEmpty = includeActiveWhenEmpty,
    )

    /** The conventional `PATH` entry for an SDK when vfox did not report one. */
    private fun defaultPathEntry(version: VfoxSdkVersion): String? {
        val home = version.home ?: return null
        val dir = File(home)
        val bin = File(dir, "bin")
        if (bin.isDirectory) return bin.absolutePath
        // Node, Python and Bun put executables directly in the version home on Windows.
        return home
    }
}
