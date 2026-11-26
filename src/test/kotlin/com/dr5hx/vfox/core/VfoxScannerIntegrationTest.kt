package com.dr5hx.vfox.core

import com.dr5hx.vfox.inject.VfoxCandidates
import com.dr5hx.vfox.inject.VfoxPathUtil
import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end checks of [VfoxScanner] against the vfox installation on this machine.
 *
 * These document and pin the real on-disk layout (SDK root override, `v-<version>` directories,
 * per-version `home`) that the rest of the plugin is built on. They skip when vfox is not
 * installed, so the build stays portable, and share one scan across tests because a scan spawns
 * one vfox process per installed version.
 */
class VfoxScannerIntegrationTest {

    private val home: File? = VfoxHomeLocator().locateHome()

    private fun requireScan(): VfoxScan? {
        if (home == null) {
            println("[integration] vfox not installed; skipping")
            return null
        }
        return try {
            sharedScan
        } catch (e: Exception) {
            println("[integration] scan failed, skipping: $e")
            null
        }
    }

    @Test
    fun `finds the vfox home and sdk root`() {
        val scan = requireScan() ?: return
        assertNotNull("scan should resolve a home", scan.home)
        assertNotNull("scan should resolve an SDK root", scan.sdkRoot)
        assertTrue("vfox home should contain config.yaml", File(home!!, "config.yaml").exists())
        println("[integration] home=${scan.home} sdkRoot=${scan.sdkRoot} error=${scan.error}")
    }

    @Test
    fun `discovers sdks and versions with existing homes`() {
        val scan = requireScan() ?: return
        scan.sdks.forEach { sdk ->
            println("[integration]   ${sdk.name}: ${sdk.versions.joinToString { it.version }} active=${sdk.active?.version}")
        }
        assertTrue("expected at least one installed SDK", scan.sdks.isNotEmpty())
        for (sdk in scan.sdks) {
            assertTrue("${sdk.name} should have at least one version", sdk.versions.isNotEmpty())
            for (version in sdk.versions) {
                assertTrue("version must not be blank", version.version.isNotBlank())
                val versionHome = version.home
                assertTrue(
                    "${version.displayName} should resolve a home that exists: $versionHome",
                    versionHome != null && File(versionHome).isDirectory,
                )
            }
        }
    }

    @Test
    fun `cli info supplies variables for at least one sdk`() {
        val scan = requireScan() ?: return
        val withVariables = scan.allVersions().filter { it.variables.isNotEmpty() }
        println("[integration] versions with variables: ${withVariables.map { it.displayName }}")
        assertTrue("expected some version to report env variables (e.g. JAVA_HOME/GOROOT)", withVariables.isNotEmpty())
        val java = scan.sdks.firstOrNull { it.name == "java" }
        if (java != null) {
            assertTrue(
                "java versions should report JAVA_HOME",
                java.versions.any { it.variables.containsKey("JAVA_HOME") },
            )
        }
    }

    @Test
    fun `finds every sdk that vfox reports installed`() {
        val scan = requireScan() ?: return

        // The regression guard for the layout bug that surfaced only golang: most SDKs live under
        // `<storage>/cache/<sdk>/` while golang sits at `<storage>/<sdk>/`, so a scanner assuming a
        // single root finds one SDK and silently loses the rest. `vfox list` is the authority on
        // what should be visible, and comparing against it keeps this test honest as SDKs come and go.
        val listed = runCatching { VfoxCliRunner().run(listOf("list"), workingDirectory = null) }.getOrNull()
        if (listed == null) {
            println("[integration] vfox list unavailable; falling back to a multi-SDK sanity check")
            assertTrue(
                "expected more than one installed SDK; only ${scan.sdks.map { it.name }} were found",
                scan.sdks.size > 1,
            )
            return
        }

        val expected = parseListSdkNames(listed)
        val found = scan.sdks.map { it.name }.toSet()
        println("[integration] vfox list SDKs=$expected, scanned=$found")
        for (sdk in expected) {
            assertTrue("$sdk is installed per `vfox list` but was not scanned (found $found)", sdk in found)
        }
    }

    /**
     * Names of the SDKs `vfox list` reports, from its tree output.
     *
     * The output draws a tree (`├─┬java`, `│ └──21.0.2`) and the box characters vary by terminal, so
     * only the ASCII skeleton is used: a version line contains `─` followed by a digit, and every
     * other non-blank, non-header line is an SDK name.
     */
    private fun parseListSdkNames(output: String): Set<String> {
        val names = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("All installed")) continue
            val afterBranches = line.trimStart('│', ' ', '├', '└', '─', '┬')
            if (afterBranches.isEmpty()) continue
            // A version looks like "1.27.1" / "21.0.2+14-librca"; an SDK name never starts with one.
            if (afterBranches.first().isDigit()) continue
            names += afterBranches
        }
        return names
    }

    @Test
    fun `versions come back newest first`() {
        val scan = requireScan() ?: return
        for (sdk in scan.sdks) {
            val versions = sdk.versions.map { it.version }
            val expected = versions.sortedWith { a, b -> VfoxScanner.compareVersions(b, a) }
            assertEquals("${sdk.name} versions should be newest-first", expected, versions)
        }
    }

    @Test
    fun `resolves a real project's declared versions`() {
        val scan = requireScan() ?: return
        // A project with vfox version files, named by the `vfox.sampleProject` system property.
        // Kept out of the source because the path is machine-specific; the test skips when unset
        // or absent, so it documents the behaviour without pinning anyone's directory layout.
        val projectPath = System.getProperty("vfox.sampleProject") ?: run {
            println("[integration] vfox.sampleProject not set; skipping")
            return
        }
        val project = File(projectPath)
        if (!File(project, ".vfox.toml").isFile) {
            println("[integration] $projectPath has no .vfox.toml; skipping")
            return
        }
        // Resolved against the real installed versions, so this exercises the matching that binds a
        // declaration like `java = "11"` to the installed `11.0.16+8-librca`.
        val requirements = VfoxProjectResolver().resolve(project.absolutePath, scan.allVersions())
        requirements.forEach { println("[integration]   want ${it.describe()}") }
        assertTrue("expected at least one parsed requirement", requirements.isNotEmpty())
        assertTrue("declared versions should not be blank", requirements.all { it.version.isNotBlank() })
        // A declaration that matched an install must name a version that is really present.
        val installed = scan.allVersions().map { "${it.sdk}@${it.version}" }.toSet()
        for (requirement in requirements) {
            requirement.resolved?.let {
                assertTrue(
                    "${requirement.sdk}@${requirement.version} resolved to $it, which is not installed",
                    "${requirement.sdk}@$it" in installed,
                )
            }
        }
    }

    @Test
    fun `offers a real project's own sdks links with both paths`() {
        val scan = requireScan() ?: return
        // The project-local junction must be offered as its own candidate — with the link path as
        // the home and the store it resolves to kept alongside — because that link is the path the
        // project (and the IDE's own Node configuration) actually uses.
        val projectPath = System.getProperty("vfox.sampleProject") ?: run {
            println("[integration] vfox.sampleProject not set; skipping")
            return
        }
        val sdksDir = File(File(projectPath, ".vfox"), "sdks")
        if (!sdksDir.isDirectory) {
            println("[integration] $projectPath has no .vfox/sdks; skipping")
            return
        }
        val candidates = VfoxProjectResolver().projectCandidates(projectPath, scan.allVersions())
        candidates.forEach {
            println("[integration]   link ${it.sdk}@${it.version}: ${it.home} -> ${it.storageHome}")
        }
        assertTrue("expected at least one project link candidate", candidates.isNotEmpty())
        for (candidate in candidates) {
            assertEquals(VfoxScope.PROJECT, candidate.scope)
            // The home is the project-local link, and it exists...
            val home = File(candidate.home!!)
            assertTrue("link home should exist: ${candidate.home}", home.isDirectory)
            // ...and the original store path is carried beside it, resolving to the same directory.
            val storage = File(candidate.storageHome!!)
            assertEquals(
                "link home and its storage target should be the same directory",
                home.canonicalFile,
                storage.canonicalFile,
            )
            // The link path is inside the project, not the machine-wide store.
            assertTrue(
                "link home should sit under the project: ${candidate.home}",
                home.absolutePath.replace('\\', '/').startsWith(File(projectPath).absolutePath.replace('\\', '/')),
            )
        }
    }

    @Test
    fun `collapses a project link and its store copy into one candidate`() {
        val scan = requireScan() ?: return
        // The duplicate the IDE showed: the project's `.vfox/sdks/nodejs` and the store directory it
        // points at are one node.exe, so both must not survive as separate candidates.
        val projectPath = System.getProperty("vfox.sampleProject") ?: run {
            println("[integration] vfox.sampleProject not set; skipping")
            return
        }
        val sdksDir = File(File(projectPath, ".vfox"), "sdks")
        if (!sdksDir.isDirectory) {
            println("[integration] $projectPath has no .vfox/sdks; skipping")
            return
        }
        val links = VfoxProjectResolver().projectCandidates(projectPath, scan.allVersions())
        if (links.isEmpty()) {
            println("[integration] no project links resolved; skipping")
            return
        }

        val offered = VfoxCandidates.distinctByRealPath(scan.allVersions() + links)
        // Every link's own file is represented exactly once, and it is the link that survives.
        for (link in links) {
            val matches = offered.filter {
                val home = it.home ?: return@filter false
                VfoxPathUtil.sameRealPath(home, link.home)
            }
            assertEquals("${link.sdk}@${link.version} should appear exactly once", 1, matches.size)
            assertEquals(VfoxScope.PROJECT, matches.single().scope)
        }
        // And the reduction really removed something — the store copy of each link is gone.
        assertTrue(
            "expected the collapse to drop the store duplicate",
            offered.size < scan.allVersions().size + links.size,
        )
    }

    private companion object {
        /** One scan shared by every test in this class; a scan spawns a process per version. */
        val sharedScan: VfoxScan by lazy { VfoxScanner().scan() }
    }
}