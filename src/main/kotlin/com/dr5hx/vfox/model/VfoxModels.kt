package com.dr5hx.vfox.model

import java.io.File

/**
 * Where a version's home points.
 *
 * vfox keeps each installed version once, in its machine-wide store, but a *project* selects a
 * version by creating a directory link at `<project>/.vfox/sdks/<sdk>`. Both are worth offering:
 * the store is the real installation, while the link is the path the project — and the IDE's own
 * configuration for it — actually uses.
 */
enum class VfoxScope {
    /** The machine-wide store, e.g. `D:\vfox\cache\nodejs\v-18.20.8\nodejs-18.20.8`. */
    GLOBAL,

    /** A project's `.vfox/sdks` link, e.g. `<project>/.vfox/sdks/nodejs`. */
    PROJECT,
}

/** One installed version of a vfox-managed SDK. */
data class VfoxSdkVersion(
    /** vfox plugin name, e.g. `nodejs`, `java`. */
    val sdk: String,
    /** Version as vfox reports it, e.g. `24.14.0`, `21.0.2+14-librca`. */
    val version: String,
    /** Installation directory (the version's home), when known. */
    val home: String? = null,
    /** Directories vfox prepends to `PATH` for this version. */
    val paths: List<String> = emptyList(),
    /** Environment variables vfox sets for this version, e.g. `JAVA_HOME`, `GOROOT`. */
    val variables: Map<String, String> = emptyMap(),
    /** Whether this is the version vfox currently activates. */
    val active: Boolean = false,
    /** Whether [home] is the machine-wide store or a project's `.vfox/sdks` link. */
    val scope: VfoxScope = VfoxScope.GLOBAL,
    /** For [VfoxScope.PROJECT]: the project directory the link belongs to. */
    val projectDir: String? = null,
    /**
     * For [VfoxScope.PROJECT]: the store directory the link resolves to — the "original location"
     * shown beside the link, so both are visible at once.
     */
    val storageHome: String? = null,
) {
    /**
     * Stable identity used for naming SDKs in the IDE, e.g. `vfox-nodejs-24.14.0`.
     *
     * A project link and the store it points at are different candidates (the IDE itself keeps both
     * for Node), so a project-scoped name carries the project's directory name to stay distinct.
     */
    val displayName: String
        get() = when (scope) {
            VfoxScope.GLOBAL -> "vfox-$sdk-$version"
            VfoxScope.PROJECT -> "vfox-$sdk-$version (${projectDir?.let { File(it).name } ?: "project"})"
        }

    companion object {
        /**
         * Orders candidates the way a project's own selections should read: **project links first**,
         * the machine-wide store after, each group keeping its existing order.
         *
         * A project's `.vfox/sdks` link is what the project actually selected, so it outranks the
         * machine-wide store everywhere the two are listed together. The sort is stable, so the
         * scanner's own ordering (newest version first) survives inside each group.
         */
        val PROJECT_FIRST: Comparator<VfoxSdkVersion> =
            compareBy { if (it.scope == VfoxScope.PROJECT) 0 else 1 }

        /** [versions] with project links ranked above the store; see [PROJECT_FIRST]. */
        fun projectFirst(versions: List<VfoxSdkVersion>): List<VfoxSdkVersion> =
            versions.sortedWith(PROJECT_FIRST)
    }
}

/** A vfox-managed SDK and every version of it installed on this machine. */
data class VfoxSdk(
    val name: String,
    val versions: List<VfoxSdkVersion>,
) {
    val active: VfoxSdkVersion? get() = versions.firstOrNull { it.active }
}

/** Complete result of scanning the local vfox installation. */
data class VfoxScan(
    val home: String? = null,
    val sdkRoot: String? = null,
    val sdks: List<VfoxSdk> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = sdks.isEmpty()

    fun allVersions(): List<VfoxSdkVersion> = sdks.flatMap { it.versions }

    fun find(sdk: String, version: String): VfoxSdkVersion? =
        sdks.firstOrNull { it.name == sdk }?.versions?.firstOrNull { it.version == version }
}
