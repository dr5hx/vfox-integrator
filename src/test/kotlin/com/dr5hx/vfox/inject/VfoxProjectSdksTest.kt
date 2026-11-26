package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Checks the shared resolution of "what does this project declare for one SDK".
 *
 * Every project-level write (Node runtime, project JDK, Go SDK) asks this, so the two rules it
 * encodes — the project's own `.vfox/sdks` link wins, and a declared version binds to an installed
 * release — are what keep those writes agreeing with each other and with the tool window.
 */
class VfoxProjectSdksTest {

    @Test
    fun `the project link is preferred over a store version`() {
        val dir = createTempDirectory("vfox-psdks-link").toFile()
        val storage = createTempDirectory("vfox-psdks-store").toFile()
        try {
            val store = File(storage, "cache/nodejs/v-18.20.8/nodejs-18.20.8").apply { mkdirs() }
            val link = File(dir, ".vfox/sdks/nodejs")
            link(link, store)

            val installed = listOf(VfoxSdkVersion("nodejs", "18.20.8", home = store.absolutePath))
            // The link path is what the project and the IDE use, so it is what a write should target.
            assertEquals(link.absolutePath, VfoxProjectSdks.declaredHome(dir.absolutePath, "nodejs", installed))
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `a declared version binds to the installed release`() {
        val dir = createTempDirectory("vfox-psdks-prefix").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\njava = \"11\"\n")
            val installed = listOf(
                VfoxSdkVersion("java", "11.0.16+8-librca", home = "/opt/java/11"),
                VfoxSdkVersion("java", "17.0.2+9-librca", home = "/opt/java/17"),
            )
            // A declaration of `11` has to resolve to the installed `11.0.16+8-librca`, or no write
            // would ever happen for a project that does not pin the exact build.
            assertEquals("/opt/java/11", VfoxProjectSdks.declaredHome(dir.absolutePath, "java", installed))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nothing is declared for an SDK the project does not mention`() {
        val dir = createTempDirectory("vfox-psdks-none").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\nnodejs = \"18.20.8\"\n")
            val installed = listOf(VfoxSdkVersion("java", "11.0.16", home = "/opt/java/11"))
            // The project says nothing about Java, so no Java write may be made.
            assertNull(VfoxProjectSdks.declaredHome(dir.absolutePath, "java", installed))
            assertNull(VfoxProjectSdks.declaredHome(dir.absolutePath, "golang", installed))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an uninstalled declaration yields nothing to write`() {
        val dir = createTempDirectory("vfox-psdks-uninstalled").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\njava = \"99\"\n")
            val installed = listOf(VfoxSdkVersion("java", "11.0.16", home = "/opt/java/11"))
            // Nothing installed matches, so there is no home to point the project at.
            assertNull(VfoxProjectSdks.declaredHome(dir.absolutePath, "java", installed))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Creates a directory link at [link] pointing to [target].
     *
     * Windows needs a junction (a plain symlink requires Developer Mode or elevation); POSIX uses a
     * symlink. Both are what vfox itself creates, and both resolve through `canonicalFile`.
     */
    private fun link(link: File, target: File) {
        link.parentFile.mkdirs()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows) {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        } else {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        }
        check(link.canonicalFile == target.canonicalFile) {
            "could not create a link at ${link.absolutePath} -> ${target.absolutePath}"
        }
    }
}
