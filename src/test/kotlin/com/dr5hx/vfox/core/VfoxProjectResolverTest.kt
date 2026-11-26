package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxScope
import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class VfoxProjectResolverTest {

    private val resolver = VfoxProjectResolver(runner = { _, _ -> null })

    @Test
    fun `parses a vfox toml tools table`() {
        val toml = """
            [tools]
            nodejs = "24.14.0"
            golang = "1.27.1"
        """.trimIndent()
        val requirements = resolver.parseToml(toml)
        assertEquals(
            listOf(
                VfoxProjectResolver.Requirement("nodejs", "24.14.0"),
                VfoxProjectResolver.Requirement("golang", "1.27.1"),
            ),
            requirements,
        )
    }

    @Test
    fun `ignores keys outside the tools table`() {
        val toml = """
            [other]
            nodejs = "1.0.0"
            [tools]
            nodejs = "24.14.0"
        """.trimIndent()
        assertEquals(
            listOf(VfoxProjectResolver.Requirement("nodejs", "24.14.0")),
            resolver.parseToml(toml),
        )
    }

    @Test
    fun `parses tool-versions file`() {
        val text = """
            nodejs 20.20.2
            golang 1.27.1
        """.trimIndent()
        assertEquals(
            listOf(
                VfoxProjectResolver.Requirement("nodejs", "20.20.2"),
                VfoxProjectResolver.Requirement("golang", "1.27.1"),
            ),
            resolver.parseToolVersions(text),
        )
    }

    @Test
    fun `parses current output ignoring N-A`() {
        val output = """
            bun -> N/A
            golang -> 1.27.1
            nodejs -> 24.14.0
        """.trimIndent()
        assertEquals(
            mapOf("golang" to "1.27.1", "nodejs" to "24.14.0"),
            resolver.parseCurrent(output),
        )
    }

    @Test
    fun `reads node-version file and strips the v prefix`() {
        val dir = createTempDirectory("vfox-project").toFile()
        try {
            File(dir, ".node-version").writeText("v20.20.2\n")
            assertEquals(
                listOf(VfoxProjectResolver.Requirement("nodejs", "20.20.2")),
                resolver.resolve(dir.absolutePath),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reads sdkmanrc java line`() {
        val dir = createTempDirectory("vfox-project").toFile()
        try {
            File(dir, ".sdkmanrc").writeText("java=21.0.2+14-librca\n")
            assertEquals(
                listOf(VfoxProjectResolver.Requirement("java", "21.0.2+14-librca")),
                resolver.resolve(dir.absolutePath),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `prefers vfox toml over tool-versions in the same directory`() {
        val dir = createTempDirectory("vfox-project").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\nnodejs = \"24.14.0\"\n")
            File(dir, ".tool-versions").writeText("nodejs 20.20.2\n")
            assertEquals(
                listOf(VfoxProjectResolver.Requirement("nodejs", "24.14.0")),
                resolver.resolve(dir.absolutePath),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `walks up to a parent directory`() {
        val root = createTempDirectory("vfox-project").toFile()
        try {
            File(root, ".vfox.toml").writeText("[tools]\ngolang = \"1.27.1\"\n")
            val nested = File(root, "a/b/c").apply { mkdirs() }
            assertEquals(
                listOf(VfoxProjectResolver.Requirement("golang", "1.27.1")),
                resolver.resolve(nested.absolutePath),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `returns empty when the project declares nothing`() {
        val dir = createTempDirectory("vfox-project").toFile()
        try {
            assertEquals(emptyList<VfoxProjectResolver.Requirement>(), resolver.resolve(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- CLI no longer shadows the project's own files ------------------------------------------

    @Test
    fun `project files win over an inherited global version reported by the CLI`() {
        // The regression: `vfox env` in a Java/Maven project still reports the machine's global
        // Node.js, and the old all-or-nothing CLI path returned that alone, dropping java and maven.
        val dir = createTempDirectory("vfox-shadow").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\njava = \"11\"\nmaven = \"3.9.15\"\n")
            val cliRunner = VfoxCommandRunner { args, _ ->
                when {
                    args.first() == "env" -> ENV_JSON_NODEJS_ONLY
                    args.first() == "current" -> "nodejs -> 24.14.0"
                    else -> null
                }
            }
            val withCli = VfoxProjectResolver(runner = cliRunner).resolve(dir.absolutePath)

            assertEquals(
                listOf(
                    VfoxProjectResolver.Requirement("java", "11"),
                    VfoxProjectResolver.Requirement("maven", "3.9.15"),
                ),
                withCli,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `falls back to the CLI only when the files declare nothing`() {
        val dir = createTempDirectory("vfox-cli").toFile()
        try {
            val cliRunner = VfoxCommandRunner { args, _ ->
                when {
                    args.first() == "env" -> ENV_JSON_NODEJS_ONLY
                    args.first() == "current" -> "nodejs -> 24.14.0"
                    else -> null
                }
            }
            assertEquals(
                listOf(VfoxProjectResolver.Requirement("nodejs", "24.14.0")),
                VfoxProjectResolver(runner = cliRunner).resolve(dir.absolutePath),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Declared version resolves to an installed one ------------------------------------------

    @Test
    fun `resolves a declared prefix to the newest installed release on that line`() {
        val dir = createTempDirectory("vfox-resolve").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\njava = \"11\"\n")
            val installed = listOf(
                VfoxSdkVersion("java", "11.0.16+8-librca", home = "/opt/java/11"),
                VfoxSdkVersion("java", "17.0.2+9-librca", home = "/opt/java/17"),
            )
            val resolved = resolver.resolve(dir.absolutePath, installed)
            assertEquals("11.0.16+8-librca", resolved.single().resolved)
            assertEquals("11.0.16+8-librca", resolved.single().effectiveVersion)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `binds a declared patch that is not installed to the nearest release beside it`() {
        val dir = createTempDirectory("vfox-nearest").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\nmaven = \"3.9.15\"\n")
            val installed = listOf(
                VfoxSdkVersion("maven", "3.8.9", home = "/opt/maven/3.8.9"),
                VfoxSdkVersion("maven", "3.9.16", home = "/opt/maven/3.9.16"),
            )
            assertEquals("3.9.16", resolver.resolve(dir.absolutePath, installed).single().resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reports an unresolved declaration as such rather than binding an unrelated version`() {
        val dir = createTempDirectory("vfox-unresolved").toFile()
        try {
            File(dir, ".vfox.toml").writeText("[tools]\njava = \"99\"\n")
            val installed = listOf(VfoxSdkVersion("java", "11.0.16+8-librca", home = "/opt/java/11"))
            val requirement = resolver.resolve(dir.absolutePath, installed).single()
            assertEquals(null, requirement.resolved)
            // With nothing matched the declared value is still what callers bind to.
            assertEquals("99", requirement.effectiveVersion)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a partial declaration does not match a full different version`() {
        // "1" must not bind "17.0.2"; only a real `1.x` line should match.
        assertEquals(null, VfoxProjectResolver.matchInstalled("1", listOf("17.0.2", "11.0.16")))
        assertEquals("17.0.2", VfoxProjectResolver.matchInstalled("17", listOf("17.0.2", "11.0.16")))
        assertEquals("24.14.0", VfoxProjectResolver.matchInstalled("24.14.0", listOf("24.14.0", "24.15.0")))
    }

    @Test
    fun `a prefix match takes the newest release on the line`() {
        // A real machine holds both `11.0.16+8-librca` and `11.0.16+8`; the librca build sorts
        // after the bare one, so it is the one a declared `11` binds.
        val versions = listOf("25.0.2+12-librca", "11.0.16+8-librca", "11.0.16+8", "8.0.342+7-librca")
        assertEquals("11.0.16+8-librca", VfoxProjectResolver.matchInstalled("11", versions))
        // A full declaration still wins exactly, without resolving up or down the line.
        assertEquals("11.0.16+8", VfoxProjectResolver.matchInstalled("11.0.16+8", versions))
    }

    // --- `.vfox/sdks` junctions -----------------------------------------------------------------

    @Test
    fun `reads the project's vfox sdks junctions`() {
        val dir = createTempDirectory("vfox-junction").toFile()
        val storage = createTempDirectory("vfox-storage").toFile()
        try {
            // Emulate vfox's layout: <storage>/cache/<sdk>/v-<ver>/<sdk>-<ver>, linked from the
            // project's `.vfox/sdks/<sdk>`.
            val javaHome = File(storage, "cache/java/v-11.0.16+8-librca/java-11.0.16+8-librca").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/java"), javaHome)

            val junctions = resolver.readSdkJunctions(dir)
            assertEquals("11.0.16+8-librca", junctions["java"])
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `a broken junction is skipped rather than thrown`() {
        val dir = createTempDirectory("vfox-broken").toFile()
        try {
            // A junction whose target no longer exists: creating it needs the target to exist, so
            // make one, link, then delete the target.
            val gone = File(dir, "gone/v-1.2.3/sdk-1.2.3").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/tool"), gone)
            gone.deleteRecursively()

            // Must not throw, and must not report the dangling entry.
            assertEquals(emptyMap<String, String>(), resolver.readSdkJunctions(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves via the junction when the version files are silent`() {
        val dir = createTempDirectory("vfox-junc-fallback").toFile()
        val storage = createTempDirectory("vfox-storage2").toFile()
        try {
            val home = File(storage, "cache/golang/v-1.27.1/golang-1.27.1").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/golang"), home)

            // Files declare nothing and the stub CLI is unavailable, so the junction stands in.
            val requirements = resolver.resolve(dir.absolutePath)
            assertEquals(listOf(VfoxProjectResolver.Requirement("golang", "1.27.1")), requirements)
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `a stale junction does not override the declared version`() {        // A project edited to `24.15.0` still carries the junction from an earlier `vfox use` of
        // `14.21.3`. The declaration is the intent, so the old junction must not win.
        val dir = createTempDirectory("vfox-stale").toFile()
        val storage = createTempDirectory("vfox-storage3").toFile()
        try {
            val old = File(storage, "cache/nodejs/v-14.21.3/nodejs-14.21.3").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/nodejs"), old)
            File(dir, ".vfox.toml").writeText("[tools]\nnodejs = \"24.15.0\"\n")

            val installed = listOf(
                VfoxSdkVersion("nodejs", "14.21.3", home = "/opt/node/14"),
                VfoxSdkVersion("nodejs", "24.15.0", home = "/opt/node/24"),
            )
            val requirement = resolver.resolve(dir.absolutePath, installed).single()
            assertEquals("24.15.0", requirement.version)
            assertEquals("24.15.0", requirement.resolved)
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    // --- Project `.vfox/sdks` links offered as candidates ---------------------------------------

    @Test
    fun `offers the project link path as the home and the store as the original`() {
        // The regression: the plugin canonicalised the link away and offered only the machine-wide
        // store, while the project (and the IDE's own Node configuration) uses the link path.
        val dir = createTempDirectory("vfox-link").toFile()
        val storage = createTempDirectory("vfox-link-store").toFile()
        try {
            val home = File(storage, "cache/nodejs/v-18.20.8/nodejs-18.20.8").apply { mkdirs() }
            val link = File(dir, ".vfox/sdks/nodejs")
            link(link, home)

            val installed = listOf(
                VfoxSdkVersion("nodejs", "18.20.8", home = home.absolutePath),
            )
            val candidate = resolver.projectCandidates(dir.absolutePath, installed).single()

            assertEquals(VfoxScope.PROJECT, candidate.scope)
            assertEquals("18.20.8", candidate.version)
            // The home is the link the project itself uses...
            assertEquals(link.absolutePath, candidate.home)
            // ...and the store it resolves to is kept beside it, not discarded.
            assertEquals(home.canonicalFile.absolutePath, File(candidate.storageHome!!).canonicalFile.absolutePath)
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `a project link to a version that is no longer installed is not offered`() {
        // Offering a link to a version the machine no longer holds would put a broken home in the
        // SDK table, so it is dropped when the installed list does not contain it.
        val dir = createTempDirectory("vfox-link-gone").toFile()
        val storage = createTempDirectory("vfox-link-gone-store").toFile()
        try {
            val home = File(storage, "cache/nodejs/v-14.21.3/nodejs-14.21.3").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/nodejs"), home)

            val installed = listOf(VfoxSdkVersion("nodejs", "18.20.8", home = "/opt/node/18"))
            assertEquals(emptyList<VfoxSdkVersion>(), resolver.projectCandidates(dir.absolutePath, installed))
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `a store version and a project link stay distinct candidates`() {
        // Same SDK and version, different scope: the IDE keeps both for Node, so both must be
        // offerable — distinguished by their home path, which is what registration dedups on.
        val dir = createTempDirectory("vfox-link-distinct").toFile()
        val storage = createTempDirectory("vfox-link-distinct-store").toFile()
        try {
            val home = File(storage, "cache/nodejs/v-18.20.8/nodejs-18.20.8").apply { mkdirs() }
            link(File(dir, ".vfox/sdks/nodejs"), home)

            val stored = VfoxSdkVersion("nodejs", "18.20.8", home = home.absolutePath)
            val linkCandidate = resolver.projectCandidates(dir.absolutePath, listOf(stored)).single()

            assertEquals(VfoxScope.GLOBAL, stored.scope)
            assertEquals(VfoxScope.PROJECT, linkCandidate.scope)
            // Different homes, so the SDK table will hold both rather than seeing one as a duplicate.
            assertEquals(false, stored.home == linkCandidate.home)
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `reads both ends of a project sdks link`() {
        val dir = createTempDirectory("vfox-link-ends").toFile()
        val storage = createTempDirectory("vfox-link-ends-store").toFile()
        try {
            val home = File(storage, "cache/golang/v-1.27.1/golang-1.27.1").apply { mkdirs() }
            val link = File(dir, ".vfox/sdks/golang")
            link(link, home)

            val found = resolver.readSdkLinks(dir).single()
            assertEquals("golang", found.sdk)
            assertEquals("1.27.1", found.version)
            assertEquals(link.absolutePath, File(found.linkPath).absolutePath)
            assertEquals(home.canonicalFile.absolutePath, File(found.targetPath).canonicalFile.absolutePath)
        } finally {
            dir.deleteRecursively()
            storage.deleteRecursively()
        }
    }

    @Test
    fun `project links rank above the store and keep their order`() {
        // "Project links first" is the display and registration rule, so it lives in the model and
        // every call site shares it. The sort must be stable: the scanner's newest-first ordering
        // has to survive inside each group, or a rescanned list would look shuffled.
        val globals = (1..3).map { VfoxSdkVersion("nodejs", "24.0.$it", home = "/store/$it") }
        val projects = (1..2).map {
            VfoxSdkVersion(
                "nodejs", "18.0.$it", home = "/proj/$it",
                scope = VfoxScope.PROJECT, projectDir = "/proj", storageHome = "/store/$it",
            )
        }

        val ordered = VfoxSdkVersion.projectFirst(globals + projects)
        assertEquals(projects + globals, ordered)
        // Stable within the project group, and irrelevant re-orderings do not change the result.
        assertEquals(listOf("18.0.1", "18.0.2"), ordered.take(2).map { it.version })
        assertEquals(listOf("24.0.1", "24.0.2", "24.0.3"), ordered.drop(2).map { it.version })
        // A store-only list is left alone, so nothing is perturbed when a project declares nothing.
        assertEquals(globals, VfoxSdkVersion.projectFirst(globals))
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
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } else {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        }
        // Fail loudly if the link could not be made, so a test never passes vacuously.
        check(link.canonicalFile == target.canonicalFile) {
            "could not create a link at ${link.absolutePath} -> ${target.absolutePath}"
        }
    }

    private companion object {
        /** What `vfox env --json` returns in a directory that inherits only the global Node.js. */
        const val ENV_JSON_NODEJS_ONLY = "{\"sdks\":{\"nodejs\":{}}}"
    }
}
