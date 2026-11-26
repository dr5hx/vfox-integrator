package com.dr5hx.vfox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Checks discovery against real directory trees on disk.
 *
 * This is the regression test for the layout bug that made the plugin see only golang. vfox puts
 * most SDKs under `<storage>/cache/<sdk>/v-<version>/` but at least one (golang) directly under
 * `<storage>/<sdk>/v-<version>/`; a scanner that assumes a single root silently finds only whatever
 * happens to live in that one place. The trees here mirror a real install closely — `v-` prefixes,
 * the nested `<sdk>-<version>` home directory, and a stray `.lock` file that must be ignored.
 *
 * The CLI runner is stubbed out so the test exercises enumeration, not `vfox info`.
 */
class VfoxScannerLayoutTest {

    /** A scratch install: a `home` directory plus the `vfox` storage root it points at. */
    private class Install(val dir: File) {
        val home: File = File(dir, "home")
        val storage: File = File(dir, "vfox")

        /** Builds `<root>/<sdk>/v-<version>/<sdk>-<version>/` and returns the nested home. */
        fun version(root: File, sdk: String, version: String): File {
            val nested = File(root, "$sdk/v-$version/$sdk-$version")
            nested.mkdirs()
            return nested
        }

        /** The `<storage>/cache` root, where most SDKs live. */
        val cache: File get() = File(storage, "cache")

        /**
         * Writes a `config.yaml` pointing SDK storage at [storage].
         *
         * The path is written the way vfox writes it — double-quoted with backslashes escaped —
         * because the parser under test is what has to cope with that, not a simplified form.
         */
        fun writeConfig() {
            home.mkdirs()
            val escaped = storage.absolutePath.replace("\\", "\\\\")
            File(home, "config.yaml").writeText("storage:\n    sdkPath: \"$escaped\"\n")
        }

        /** A locator pointed at [home] through `VFOX_HOME`, bypassing the real machine's state. */
        fun locator() = VfoxHomeLocator(
            environment = { if (it == "VFOX_HOME") home.absolutePath else null },
            userHome = home.absolutePath,
        )

        fun scan() = VfoxScanner(locator(), VfoxCommandRunner { _, _ -> null }).scan()
    }

    private fun withInstall(body: (Install) -> Unit) {
        val dir = createTempDirectory("vfox-layout").toFile()
        try {
            body(Install(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `finds SDKs under cache and under the storage root`() = withInstall { install ->
        // The two shapes seen in the wild.
        install.version(install.cache, "nodejs", "24.14.0")
        install.version(install.cache, "java", "21.0.2")
        install.version(install.storage, "golang", "1.27.1")
        install.writeConfig()

        val scan = install.scan()

        val names = scan.sdks.map { it.name }
        assertEquals("all three SDKs should be found: $names", 3, scan.sdks.size)
        assertTrue("nodejs should be found under cache/: $names", names.contains("nodejs"))
        assertTrue("java should be found under cache/: $names", names.contains("java"))
        assertTrue("golang should be found at the storage root: $names", names.contains("golang"))
    }

    @Test
    fun `reads every installed version, newest first`() = withInstall { install ->
        install.version(install.cache, "nodejs", "14.21.3")
        install.version(install.cache, "nodejs", "24.14.0")
        install.version(install.cache, "nodejs", "20.20.2")
        install.writeConfig()

        val nodejs = install.scan().sdks.first { it.name == "nodejs" }

        assertEquals("all three versions should be read", 3, nodejs.versions.size)
        // Newest first, compared numerically (24 > 20 > 14), not lexically.
        assertEquals(listOf("24.14.0", "20.20.2", "14.21.3"), nodejs.versions.map { it.version })
    }

    @Test
    fun `a version is not listed twice when it exists in both layouts`() = withInstall { install ->
        // Deliberately place the same version in both candidate locations.
        install.version(install.cache, "java", "21.0.2")
        install.version(install.storage, "java", "21.0.2")
        install.writeConfig()

        val java = install.scan().sdks.first { it.name == "java" }

        assertEquals("a version present in both layouts must appear once", 1, java.versions.size)
    }

    @Test
    fun `ignores lock files and stray directories`() = withInstall { install ->
        install.version(install.cache, "nodejs", "24.14.0")
        // Real installs leave these behind; neither is a version.
        val nodeDir = File(install.cache, "nodejs")
        File(nodeDir, ".v-20.20.2.lock").writeText("")
        File(nodeDir, "downloads").mkdirs()
        install.writeConfig()

        val nodejs = install.scan().sdks.first { it.name == "nodejs" }

        assertEquals(listOf("24.14.0"), nodejs.versions.map { it.version })
    }

    @Test
    fun `resolves the nested home directory for a version`() = withInstall { install ->
        val expected = install.version(install.cache, "nodejs", "24.14.0")
        install.writeConfig()

        val nodejs = install.scan().sdks.first { it.name == "nodejs" }.versions.first()

        assertEquals(
            "home should be the nested <sdk>-<version> directory",
            expected.absolutePath,
            nodejs.home,
        )
    }

    @Test
    fun `reports an error rather than an empty success when nothing is installed`() = withInstall { install ->
        install.writeConfig()

        val scan = install.scan()

        assertTrue("an empty scan should stay empty", scan.isEmpty)
        assertTrue("an empty scan should explain itself", scan.error != null)
    }
}
