package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxScope
import com.dr5hx.vfox.model.VfoxSdkVersion
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * Determines which SDK versions a *project* asks for.
 *
 * The project's own version files are the authority: `.vfox.toml`, `.tool-versions`, and the legacy
 * files vfox is configured to honour (`.node-version`, `.nvmrc`, `.sdkmanrc`), read from the project
 * directory upwards. The `vfox` CLI is consulted only when those files declare nothing, because the
 * CLI reports *inherited global* versions too — a project with a Go-only `.vfox.toml` would
 * otherwise be told it "wants" the machine's global Node.js.
 *
 * A version a project declares is not always installed exactly (`java = "11"` against a machine with
 * `11.0.16+8-librca`). Each [Requirement] therefore carries both the declared string and the
 * installed version it [resolves][Requirement.resolved] to, matched against the installed list the
 * caller passes in. The project's `.vfox/sdks` junctions are read as a *last-resort source* of
 * requirements — never preferred over a declaration, since a junction is derived state that goes
 * stale as soon as the declaration changes.
 */
class VfoxProjectResolver(
    private val runner: VfoxCommandRunner = VfoxCliRunner(),
) {

    /**
     * A version a project requests for one SDK.
     *
     * @property version the declared value as written, e.g. `11`, `3.9.15`, `24.14.0`.
     * @property resolved the installed version this binds to, e.g. `11.0.16+8-librca`; null when
     *   nothing installed matches, or when no installed list was supplied.
     */
    data class Requirement(
        val sdk: String,
        val version: String,
        val resolved: String? = null,
    ) {
        /**
         * The version to actually bind an SDK to.
         *
         * The resolved installed version when there is one, else the declared value — which is what
         * a caller with no installed list (or an unmatched declaration) gets, so it degrades to the
         * raw declaration rather than to nothing.
         */
        val effectiveVersion: String get() = resolved ?: version

        /** A short human description: `java@11 -> 11.0.16+8-librca`, or just `java@11` when exact. */
        fun describe(): String =
            if (resolved != null && resolved != version) "$sdk@$version -> $resolved" else "$sdk@$version"
    }

    /**
     * Resolves the project's requested versions.
     *
     * @param basePath the project's base directory (as a filesystem path).
     * @param installed every version found by the last scan, used to resolve a declared version to
     *   one that is actually on disk. Empty when unknown, in which case [Requirement.resolved] stays
     *   null and callers fall back to the declared value.
     * @return the requested versions, empty when the project declares none.
     */
    fun resolve(basePath: String, installed: List<VfoxSdkVersion> = emptyList()): List<Requirement> {
        val declared = fromFiles(basePath)
        val requirements = when {
            declared.isNotEmpty() -> declared
            // Files declare nothing, so fall back to the CLI — which additionally reports the
            // global versions vfox would inherit here. Only when even that yields nothing (the CLI
            // is unavailable, say) do the project's own junctions stand in, so a project that only
            // ever ran `vfox use` is still recognised.
            else -> fromCli(basePath).orEmpty().ifEmpty { requirementsFromJunctions(File(basePath)) }
        }
        return applyInstalled(requirements, installed)
    }

    /**
     * Asks vfox what the directory resolves to.
     *
     * `vfox env --json` reports the resolved environment as `{"sdks": {"nodejs": {...}}, "paths":
     * [...]}`. The SDK names are the keys, but the *versions* are not in that document, so each
     * name is resolved through `vfox current`, which reports versions per SDK.
     */
    private fun fromCli(basePath: String): List<Requirement>? {
        val envJson = runner.run(listOf("env", "--json"), workingDirectory = basePath)
        if (envJson == null) {
            thisLogger().debug("vfox env --json unavailable for $basePath; falling back to file parsing")
            return null
        }
        val names = runCatching {
            Json.objectAt(Json.parseObject(envJson), "sdks").keys
        }.getOrNull().orEmpty()
        if (names.isEmpty()) return emptyList()

        val currentOutput = runner.run(listOf("current"), workingDirectory = basePath) ?: return null
        val active = parseCurrent(currentOutput)
        return names.mapNotNull { name ->
            active[name]?.let { Requirement(name, it) }
        }
    }

    /** Parses `vfox current` output of the form `nodejs -> 24.14.0` (ignoring `N/A`). */
    internal fun parseCurrent(output: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (line in output.lineSequence()) {
            val parts = line.split("->").map { it.trim() }
            if (parts.size != 2) continue
            val (name, version) = parts
            if (name.isNotEmpty() && version.isNotEmpty() && version != "N/A") {
                result[name] = version
            }
        }
        return result
    }

    /**
     * Parses version files directly.
     *
     * Only the nearest scope is read per SDK, mirroring vfox's own precedence: `.vfox.toml` first,
     * then `.tool-versions`, then the legacy files. The search walks up from [basePath] so a version
     * file in a parent directory applies, as it does for vfox.
     */
    private fun fromFiles(basePath: String): List<Requirement> {
        var dir: File? = File(basePath).absoluteFile
        while (dir != null) {
            val found = parseDirectory(dir)
            if (found.isNotEmpty()) return found
            dir = dir.parentFile
        }
        return emptyList()
    }

    private fun parseDirectory(dir: File): List<Requirement> {
        File(dir, ".vfox.toml").takeIf { it.isFile }?.let { file ->
            val req = parseToml(runCatching { file.readText() }.getOrNull().orEmpty())
            if (req.isNotEmpty()) return req
        }
        File(dir, ".tool-versions").takeIf { it.isFile }?.let { file ->
            val req = parseToolVersions(runCatching { file.readText() }.getOrNull().orEmpty())
            if (req.isNotEmpty()) return req
        }
        val requirements = ArrayList<Requirement>()
        File(dir, ".node-version").takeIf { it.isFile }?.let { file ->
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { requirements += Requirement("nodejs", normalizeNodeVersion(it)) }
        }
        File(dir, ".nvmrc").takeIf { it.isFile }?.let { file ->
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { requirements += Requirement("nodejs", normalizeNodeVersion(it)) }
        }
        File(dir, ".sdkmanrc").takeIf { it.isFile }?.let { file ->
            runCatching { file.readText() }.getOrNull()?.let { text ->
                for (line in text.lineSequence()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("java=")) continue
                    val version = trimmed.removePrefix("java=").trim()
                    if (version.isNotEmpty()) requirements += Requirement("java", version)
                }
            }
        }
        return requirements
    }

    /** Reads the `[tools]` table of a `.vfox.toml` file, e.g. `nodejs = "24.14.0"`. */
    internal fun parseToml(text: String): List<Requirement> {
        val result = ArrayList<Requirement>()
        var inTools = false
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                inTools = line == "[tools]"
                continue
            }
            if (!inTools) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().trim('"', '\'')
            val value = line.substring(eq + 1).trim().trim('"', '\'')
            if (key.isNotEmpty() && value.isNotEmpty()) result += Requirement(key, value)
        }
        return result
    }

    /** Reads an asdf-style `.tool-versions` file, e.g. `nodejs 24.14.0`. */
    internal fun parseToolVersions(text: String): List<Requirement> {
        val result = ArrayList<Requirement>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) result += Requirement(parts[0], parts[1])
        }
        return result
    }

    /**
     * Reads the project's `.vfox/sdks` junctions: `<basePath>/.vfox/sdks/<sdk>` -> the version
     * directory vfox selected for the project, as `sdk -> version`.
     *
     * vfox stores each selected SDK as a directory junction (Windows) or symlink pointing into its
     * storage, so the target's `v-<version>` segment names the version. A junction whose target is
     * gone resolves to nothing useful and is skipped rather than reported, and a plain directory
     * (no `v-` segment in its real path) is not a junction at all and is skipped the same way.
     *
     * `Files.isSymbolicLink` is deliberately not used: a Windows junction reports `false` there,
     * while `getCanonicalPath` does follow it.
     */
    internal fun readSdkJunctions(basePath: File): Map<String, String> =
        readSdkLinks(basePath).associate { it.sdk to it.version }

    /** A project's `.vfox/sdks/<sdk>` selection: the link itself and where it points. */
    internal data class SdkLink(
        /** The SDK name, i.e. the link's own directory name. */
        val sdk: String,
        /** The version, read from the target's `v-<version>` segment. */
        val version: String,
        /** The link path the project itself uses, e.g. `<project>/.vfox/sdks/nodejs`. */
        val linkPath: String,
        /** The store directory the link resolves to, e.g. `D:\vfox\cache\nodejs\v-18.20.8\...`. */
        val targetPath: String,
    )

    /**
     * Reads the project's `.vfox/sdks` links, keeping **both** ends of each one.
     *
     * This is what lets the plugin offer the project-local path as its own candidate rather than
     * only the machine-wide store: the link is the path the project (and the IDE's own configuration
     * for it) actually points at, while the target is the real installation. [readSdkJunctions]
     * keeps only the version, for callers that just need to know *what* was selected.
     */
    internal fun readSdkLinks(basePath: File): List<SdkLink> {
        val sdksDir = File(File(basePath, ".vfox"), "sdks")
        if (!sdksDir.isDirectory) return emptyList()

        val result = ArrayList<SdkLink>()
        for (child in sdksDir.listFiles().orEmpty()) {
            if (!child.isDirectory) continue
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
            val version = versionFromPath(canonical) ?: continue
            result += SdkLink(
                sdk = child.name,
                version = version,
                linkPath = child.absolutePath,
                targetPath = canonical.absolutePath,
            )
        }
        return result
    }

    /**
     * The project's `.vfox/sdks` selections as candidates, one per link.
     *
     * Each carries the **project-local link path** as its home (not the resolved store), so an SDK
     * registered from it matches what the project and the IDE already use, and the store path
     * alongside as [VfoxSdkVersion.storageHome] so both remain visible.
     *
     * A link whose version is not among [installed] is skipped: the store entry there is what
     * actually exists, and offering a link to a version the machine no longer holds would put a
     * broken home in the SDK table.
     */
    fun projectCandidates(
        basePath: String,
        installed: List<VfoxSdkVersion> = emptyList(),
    ): List<VfoxSdkVersion> {
        val projectDir = File(basePath).absolutePath
        return readSdkLinks(File(basePath)).mapNotNull { link ->
            // Take the PATH/variable details from the scanned store entry for the same version.
            val stored = installed.firstOrNull { it.sdk == link.sdk && it.version == link.version }
            // When a scan has run and does not know this version, the machine no longer holds it —
            // a link to it would register a home that is not there, so it is dropped.
            if (installed.isNotEmpty() && stored == null) return@mapNotNull null
            VfoxSdkVersion(
                sdk = link.sdk,
                version = link.version,
                home = link.linkPath,
                paths = stored?.paths.orEmpty(),
                variables = stored?.variables.orEmpty(),
                active = stored?.active ?: false,
                scope = VfoxScope.PROJECT,
                projectDir = projectDir,
                storageHome = link.targetPath,
            )
        }
    }

    /** Extracts `<ver>` from the `v-<ver>` segment of a resolved SDK path, or null when absent. */
    private fun versionFromPath(dir: File): String? {
        for (segment in dir.path.replace('\\', '/').split('/').asReversed()) {
            if (segment.startsWith("v-")) {
                return segment.removePrefix("v-").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /**
     * Turns the project's `.vfox/sdks` junctions into requirements.
     *
     * The last resort, used when neither version files nor the CLI answer — a project configured
     * only with `vfox use` keeps its selection in these junctions and nowhere else.
     */
    private fun requirementsFromJunctions(basePath: File): List<Requirement> {
        val junctionVersion = readSdkJunctions(basePath)
        return junctionVersion.map { (sdk, version) -> Requirement(sdk, version) }
    }

    /**
     * Fills in each requirement's [Requirement.resolved] against [installed].
     *
     * Only the declared value is matched — a junction is deliberately *not* preferred here. A
     * junction records the version vfox selected the last time it ran in the project and goes stale
     * the moment the declaration changes (a project edited to `nodejs = "24.15.0"` still points at
     * the old `14.21.3`), so binding it would resurrect a version the project no longer asks for.
     * The declaration is the intent; the junction only *sources* requirements when nothing else does.
     */
    private fun applyInstalled(
        requirements: List<Requirement>,
        installed: List<VfoxSdkVersion>,
    ): List<Requirement> {
        if (requirements.isEmpty()) return emptyList()
        val bySdk: Map<String, List<String>> = installed
            .groupBy { it.sdk }
            .mapValues { (_, versions) -> versions.map { it.version } }

        return requirements.map { requirement ->
            val available = bySdk[requirement.sdk].orEmpty()
            requirement.copy(resolved = matchInstalled(requirement.version, available))
        }
    }

    /** Strips a leading `v` (`.nvmrc` commonly holds `v20.20.2`) and any `lts/` prefix noise. */
    private fun normalizeNodeVersion(raw: String): String =
        raw.trim().removePrefix("v").substringAfterLast('/')

    companion object {
        /**
         * Binds a declared version to one that is installed, or null when none is close enough.
         *
         * Matching is layered, most exact first:
         *
         *  1. an exact match (`24.14.0` -> `24.14.0`);
         *  2. a segment prefix, so a partial declaration takes the newest release on that line
         *     (`11` -> `11.0.16+8-librca`, `2.7` -> `2.7`);
         *  3. the same major.minor, so a declared patch that is not installed still binds the
         *     nearest release beside it (`3.9.15` -> `3.9.16`), which is what vfox resolves too.
         *
         * Nothing matching at any level returns null, so an uninstalled declaration is reported as
         * such rather than bound to an unrelated version.
         */
        fun matchInstalled(declared: String, available: List<String>): String? {
            if (declared.isBlank() || available.isEmpty()) return null
            available.firstOrNull { it == declared }?.let { return it }

            // `startsWith("$declared.")` rather than a raw prefix so "1" cannot match "17.0.2".
            available.filter { it.startsWith("$declared.") }
                .takeIf { it.isNotEmpty() }
                ?.let { return newest(it) }

            val parts = declared.split('.')
            if (parts.size >= 2) {
                val line = "${parts[0]}.${parts[1]}"
                available.filter { it == line || it.startsWith("$line.") }
                    .takeIf { it.isNotEmpty() }
                    ?.let { return newest(it) }
            }
            return null
        }

        /** The newest of [versions] by vfox's own version ordering. */
        private fun newest(versions: List<String>): String =
            versions.maxWithOrNull { a, b -> VfoxScanner.compareVersions(a, b) } ?: versions.first()

        /**
         * Resolves [basePath] against the versions the last scan found.
         *
         * The convenience the UI and build-tool integrations use, so none of them has to remember to
         * pass the installed list — forgetting it would silently leave every declaration unresolved
         * and re-break the version-matching this exists to fix.
         */
        fun forLastScan(basePath: String): List<Requirement> =
            VfoxProjectResolver().resolve(basePath, VfoxService.getInstance().versions())
    }
}
