package com.dr5hx.vfox.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Checks the path comparison the injectors dedup on.
 *
 * The behaviour that matters is links: vfox records a project's selection as a directory link at
 * `<project>/.vfox/sdks/<sdk>` pointing into the machine-wide store, so the same executable is
 * reachable under two names. The IDE's own lists do not canonicalize, so a plugin that compares
 * literal paths registers the same Node/Go twice — which is exactly the duplicate this guards.
 */
class VfoxPathUtilTest {

    @Test
    fun `a link and its target are the same real path`() {
        val root = createTempDirectory("vfox-paths").toFile()
        try {
            val target = File(root, "store/cache/nodejs/v-18.20.8/nodejs-18.20.8").apply { mkdirs() }
            File(target, "node.exe").writeText("")
            val link = File(root, "project/.vfox/sdks/nodejs")
            link(link, target)

            // The link and the store are two names for one directory...
            assertTrue(VfoxPathUtil.sameRealPath(link.absolutePath, target.absolutePath))
            // ...and for the file inside it, which is what the Node injector compares.
            assertTrue(
                VfoxPathUtil.sameRealPath(
                    File(link, "node.exe").absolutePath,
                    File(target, "node.exe").absolutePath,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `two genuinely different directories are not the same real path`() {
        val root = createTempDirectory("vfox-paths-diff").toFile()
        try {
            val a = File(root, "store/cache/nodejs/v-18.20.8").apply { mkdirs() }
            val b = File(root, "store/cache/nodejs/v-20.20.2").apply { mkdirs() }
            assertFalse(VfoxPathUtil.sameRealPath(a.absolutePath, b.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a path with a different spelling of the same file compares equal`() {
        val root = createTempDirectory("vfox-paths-spell").toFile()
        try {
            val dir = File(root, "abc").apply { mkdirs() }
            // `..` is collapsed by canonicalization, so an equivalent spelling still matches.
            val odd = File(root, "abc/../abc").absolutePath
            assertNotEquals(dir.absolutePath, odd)
            assertTrue(VfoxPathUtil.sameRealPath(dir.absolutePath, odd))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a missing path still compares by its normalized form`() {
        val root = createTempDirectory("vfox-paths-gone").toFile()
        try {
            val gone = File(root, "no-such-dir").absolutePath
            // A path that does not exist has no links to resolve, so canonicalization falls back to
            // the literal form: identical spellings match, and different ones do not.
            assertTrue(VfoxPathUtil.sameRealPath(gone, gone))
            assertFalse(VfoxPathUtil.sameRealPath(gone, "${gone}-other"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `null never matches`() {
        assertFalse(VfoxPathUtil.sameRealPath(null, "/x"))
        assertFalse(VfoxPathUtil.sameRealPath("/x", null))
        assertFalse(VfoxPathUtil.sameRealPath(null, null))
    }

    @Test
    fun `canonical keeps a missing path usable rather than failing`() {
        val root = createTempDirectory("vfox-paths-canon").toFile()
        try {
            assertEquals(VfoxPathUtil.normalize(root.absolutePath), VfoxPathUtil.canonical(root.absolutePath))
            // A nonexistent path is normalized, not rejected, so comparisons never blow up on it.
            val gone = File(root, "nope").absolutePath
            assertEquals(VfoxPathUtil.normalize(gone), VfoxPathUtil.canonical(gone))
        } finally {
            root.deleteRecursively()
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
