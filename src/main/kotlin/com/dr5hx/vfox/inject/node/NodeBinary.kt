package com.dr5hx.vfox.inject.node

import com.dr5hx.vfox.model.VfoxSdkVersion
import java.io.File

/**
 * Locates the `node` executable inside a Node installation.
 *
 * Shared so registration and the project-interpreter write cannot disagree about where a version's
 * executable is: both have to resolve the same file, or the interpreter registered and the one
 * selected would differ.
 *
 * On Windows the executable sits directly in the version home (`node.exe`); elsewhere it is under
 * `bin/`. Both are checked regardless of platform, so a cross-platform scan still resolves. A
 * project's `.vfox/sdks/nodejs` is a directory link, so its contents are found through it exactly as
 * through the store directory it points at.
 */
internal object NodeBinary {

    /** The `node` executable for [version], or null when its home is unknown or holds none. */
    fun forVersion(version: VfoxSdkVersion): File? = version.home?.let { inHome(File(it)) }

    /** The `node` executable inside [home], or null when [home] is not a directory holding one. */
    fun inHome(home: File): File? {
        if (!home.isDirectory) return null
        return listOf(
            File(home, "node.exe"),
            File(home, "bin/node"),
            File(home, "bin/node.exe"),
        ).firstOrNull { it.isFile }
    }
}
