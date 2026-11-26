package com.dr5hx.vfox.inject

import com.dr5hx.vfox.model.VfoxScope
import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Checks the reduction to one candidate per real executable.
 *
 * The case this guards is the duplicate the IDE shows: a project's `.vfox/sdks/nodejs/node.exe`
 * and the store copy it points at are one file, but the IDE's interpreter list compares paths
 * literally, so offering both lists the same Node twice.
 */
class VfoxCandidatesTest {

    @Test
    fun `a project link and its store target collapse to the link`() {
        val root = createTempDirectory("vfox-cand").toFile()
        try {
            val store = File(root, "store/cache/nodejs/v-18.20.8/nodejs-18.20.8").apply { mkdirs() }
            val link = File(root, "project/.vfox/sdks/nodejs")
            linkDir(link, store)

            val storeCandidate = VfoxSdkVersion("nodejs", "18.20.8", home = store.absolutePath)
            val linkCandidate = VfoxSdkVersion(
                "nodejs", "18.20.8", home = link.absolutePath,
                scope = VfoxScope.PROJECT, projectDir = File(root, "project").absolutePath,
                storageHome = store.canonicalFile.absolutePath,
            )

            // Offered store-first, as a scan followed by the project links would produce.
            val reduced = VfoxCandidates.distinctByRealPath(listOf(storeCandidate, linkCandidate))
            assertEquals(1, reduced.size)
            // The link survives — the path the project and the IDE use — and keeps the store beside it.
            assertEquals(VfoxScope.PROJECT, reduced.single().scope)
            assertEquals(link.absolutePath, reduced.single().home)
            assertEquals(store.canonicalFile.absolutePath, File(reduced.single().storageHome!!).canonicalFile.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `distinct versions are all kept`() {
        val root = createTempDirectory("vfox-cand-distinct").toFile()
        try {
            val a = File(root, "store/cache/nodejs/v-18.20.8").apply { mkdirs() }
            val b = File(root, "store/cache/nodejs/v-20.20.2").apply { mkdirs() }
            val reduced = VfoxCandidates.distinctByRealPath(
                listOf(
                    VfoxSdkVersion("nodejs", "18.20.8", home = a.absolutePath),
                    VfoxSdkVersion("nodejs", "20.20.2", home = b.absolutePath),
                ),
            )
            assertEquals(listOf("18.20.8", "20.20.2"), reduced.map { it.version })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the same path offered twice is kept once`() {
        val root = createTempDirectory("vfox-cand-dup").toFile()
        try {
            val dir = File(root, "store/cache/java/v-11.0.16").apply { mkdirs() }
            val twice = listOf(
                VfoxSdkVersion("java", "11.0.16", home = dir.absolutePath),
                VfoxSdkVersion("java", "11.0.16", home = dir.absolutePath),
            )
            assertEquals(1, VfoxCandidates.distinctByRealPath(twice).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `entries with an unknown home are never dropped`() {
        // Nothing may be lost merely for being unresolvable; two such entries for the same SDK and
        // version are the same candidate, but different SDKs stay distinct.
        val reduced = VfoxCandidates.distinctByRealPath(
            listOf(
                VfoxSdkVersion("rust", "1.80.0", home = null),
                VfoxSdkVersion("zig", "0.13.0", home = null),
                VfoxSdkVersion("rust", "1.80.0", home = null),
            ),
        )
        assertEquals(listOf("rust", "zig"), reduced.map { it.sdk })
    }

    @Test
    fun `project links come first and the reduction is stable`() {
        val root = createTempDirectory("vfox-cand-order").toFile()
        try {
            val store = File(root, "store/cache/nodejs/v-20.20.2").apply { mkdirs() }
            val other = File(root, "store/cache/nodejs/v-18.20.8").apply { mkdirs() }
            val reduced = VfoxCandidates.distinctByRealPath(
                listOf(
                    VfoxSdkVersion("nodejs", "20.20.2", home = store.absolutePath),
                    VfoxSdkVersion(
                        "nodejs", "18.20.8", home = other.absolutePath,
                        scope = VfoxScope.PROJECT, projectDir = root.absolutePath,
                        storageHome = other.canonicalFile.absolutePath,
                    ),
                ),
            )
            // The project link is first, and the untouched store entry keeps its relative order.
            assertEquals(VfoxScope.PROJECT, reduced.first().scope)
            assertEquals("18.20.8", reduced.first().version)
            assertEquals("20.20.2", reduced.last().version)
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
    private fun linkDir(link: File, target: File) {
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
