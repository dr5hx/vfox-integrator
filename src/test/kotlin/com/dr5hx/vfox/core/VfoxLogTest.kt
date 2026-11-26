package com.dr5hx.vfox.core

import com.dr5hx.vfox.inject.InjectResult
import com.dr5hx.vfox.model.VfoxScan
import com.dr5hx.vfox.model.VfoxSdk
import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory
/**
 * Checks the vfox log: both the line format and the real file write.
 *
 * The log file exists to answer "what did the scan actually see?", so it is the deliverable here:
 * every line must carry the SDK, its version, whether it is active, and where the plugin thinks its
 * home is — and writing it must actually produce a file.
 */
class VfoxLogTest {

    @Test
    fun `line carries sdk, version, active flag and home`() {
        val line = VfoxLog.versionLine(
            VfoxSdkVersion(
                sdk = "java",
                version = "21.0.2+14-librca",
                home = "D:/vfox/cache/java/v-21.0.2+14-librca/java-21.0.2+14-librca",
                active = true,
            ),
        )
        assertTrue("should name the sdk: $line", line.contains("java"))
        assertTrue("should carry the version: $line", line.contains("21.0.2+14-librca"))
        assertTrue("should mark the active version: $line", line.contains("active"))
        assertTrue("should carry the home: $line", line.contains("D:/vfox/cache/java/"))
    }

    @Test
    fun `line says so when the home is unknown`() {
        val line = VfoxLog.versionLine(VfoxSdkVersion(sdk = "nodejs", version = "24.14.0"))
        assertTrue("should be explicit about a missing home: $line", line.contains("(no home)"))
    }

    @Test
    fun `line lists environment variable names when present`() {
        val line = VfoxLog.versionLine(
            VfoxSdkVersion(
                sdk = "java",
                version = "21.0.2",
                home = "/opt/java",
                variables = mapOf("JAVA_HOME" to "/opt/java", "PATH" to "/opt/java/bin"),
            ),
        )
        assertTrue("should name the variables it will set: $line", line.contains("vars="))
        assertTrue(line.contains("JAVA_HOME"))
    }

    @Test
    fun `line does not mention variables when there are none`() {
        val line = VfoxLog.versionLine(VfoxSdkVersion(sdk = "nodejs", version = "24.14.0", home = "C:/node"))
        assertTrue("should stay quiet about absent variables: $line", !line.contains("vars="))
    }

    @Test
    fun `line does not claim an inactive version is active`() {
        val line = VfoxLog.versionLine(VfoxSdkVersion(sdk = "java", version = "17.0.2", home = "/opt/jdk17"))
        assertTrue("inactive version must not say 'active': $line", !line.contains("active"))
    }

    @Test
    fun `registration line counts every outcome`() {
        val line = VfoxLog.registrationLine(
            mapOf(
                InjectResult.ADDED to 3,
                InjectResult.ALREADY_PRESENT to 2,
                InjectResult.SKIPPED to 1,
                InjectResult.FAILED to 1,
            ),
        )
        assertTrue(line, line.contains("3 added"))
        assertTrue(line, line.contains("2 already present"))
        assertTrue(line, line.contains("1 skipped"))
        assertTrue(line, line.contains("1 failed"))
    }

    @Test
    fun `registration line treats missing outcomes as zero`() {
        val line = VfoxLog.registrationLine(mapOf(InjectResult.ADDED to 1))
        assertTrue(line, line.contains("1 added"))
        assertTrue(line, line.contains("0 already present"))
    }

    @Test
    fun `scan lines carry a header and one line per version`() {
        val scan = VfoxScan(
            home = "C:/Users/x/.vfox",
            sdkRoot = "D:/vfox",
            sdks = listOf(
                VfoxSdk("java", listOf(version("java", "21.0.2"), version("java", "17.0.2"))),
                VfoxSdk("nodejs", listOf(version("nodejs", "24.14.0", active = true))),
            ),
        )
        val lines = VfoxLog.scanLines(scan)

        assertEquals("header + three versions", 4, lines.size)
        assertTrue(lines.first(), lines.first().startsWith("scan:"))
        assertTrue(lines.first(), lines.first().contains("2 SDK(s), 3 version(s)"))
        assertTrue(lines.first(), lines.first().contains("home=C:/Users/x/.vfox"))
        // Versions are indented so the header stands out.
        assertTrue(lines[1], lines[1].startsWith("  "))
    }

    @Test
    fun `scan header records the error when there is one`() {
        val lines = VfoxLog.scanLines(VfoxScan(error = "vfox home directory not found"))
        assertEquals(1, lines.size)
        assertTrue(lines.first(), lines.first().contains("error=vfox home directory not found"))
    }

    @Test
    fun `writing actually creates the file and appends`() {
        val dir = createTempDirectory("vfox-log").toFile()
        try {
            val file = java.io.File(dir, "nested/vfox-integrator.log")
            assertTrue("should not exist yet", !file.isFile)

            VfoxLog.writeTo(file, listOf("first run"))
            VfoxLog.writeTo(file, listOf("second run"))

            assertTrue("file should have been created", file.isFile)
            val text = file.readText()
            assertTrue(text, text.contains("first run"))
            assertTrue(text, text.contains("second run"))
            // Appended, not overwritten: both runs survive.
            assertTrue(text, text.indexOf("first run") < text.indexOf("second run"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `writing nothing leaves no file behind`() {
        val dir = createTempDirectory("vfox-log-empty").toFile()
        try {
            val file = java.io.File(dir, "vfox-integrator.log")
            VfoxLog.writeTo(file, emptyList())
            assertTrue("an empty write should not create a file", !file.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a write failure is swallowed rather than thrown`() {
        // A directory used as the target file cannot be appended to; the scan must carry on.
        val dir = createTempDirectory("vfox-log-locked").toFile()
        try {
            VfoxLog.writeTo(dir, listOf("this cannot be written"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun version(sdk: String, v: String, active: Boolean = false) =
        VfoxSdkVersion(sdk = sdk, version = v, home = "/opt/$sdk/$v", active = active)
}
