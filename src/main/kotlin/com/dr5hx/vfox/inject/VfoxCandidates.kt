package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxSdkVersion

/**
 * The candidate list the plugin offers, as one entry per real executable.
 *
 * A project's `.vfox/sdks/<sdk>` link and the store directory it points at are the same binary under
 * two names. The IDE's SDK tables and interpreter lists compare paths literally, so offering both
 * registers the same tool twice — the duplicate this exists to prevent. Collapsing them here, in one
 * place, is what keeps the tool window's list and what gets registered in step.
 */
object VfoxCandidates {

    /**
     * [versions] reduced to one entry per real file, with project links preferred.
     *
     * Project links rank first ([VfoxSdkVersion.PROJECT_FIRST]) and a later duplicate of the same
     * file is dropped, so where a project link and its store target are both present the **link**
     * survives — the path the project, and the IDE's own configuration for it, actually uses. The
     * store path is not lost: a project candidate carries it as
     * [VfoxSdkVersion.storageHome], and the tool window shows it in the *Original* column.
     *
     * A version whose home is unknown cannot be compared and is kept as-is; nothing is ever dropped
     * for being unresolvable, only for naming a file already present.
     */
    fun distinctByRealPath(versions: List<VfoxSdkVersion>): List<VfoxSdkVersion> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<VfoxSdkVersion>(versions.size)
        for (version in versions.sortedWith(VfoxSdkVersion.PROJECT_FIRST)) {
            val key = version.home
                ?.let { VfoxPathUtil.canonical(it) }
                // Two unresolvable entries for the same SDK and version are the same candidate.
                ?: "${version.sdk}@${version.version}"
            if (seen.add(key)) result += version
        }
        return result
    }
}
