package com.dr5hx.vfox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.path.createTempDirectory

class VfoxHomeLocatorTest {

    @Test
    fun `prefers VFOX_HOME when set`() {
        val temp = createTempDirectory("vfox-home").toFile()
        try {
            // VFOX_HOME is the only environment-based source, and the most explicit one.
            val locator = VfoxHomeLocator(
                environment = { if (it == "VFOX_HOME") temp.absolutePath else null },
                userHome = "/nonexistent",
            )
            assertEquals(temp.canonicalFile, locator.locateHome()?.canonicalFile)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `falls back to dot-vfox in user home`() {
        val userHome = createTempDirectory("fake-user-home").toFile()
        try {
            val dotVfox = java.io.File(userHome, ".vfox").apply { mkdirs() }
            val locator = VfoxHomeLocator(environment = { null }, userHome = userHome.absolutePath)
            assertEquals(dotVfox.canonicalFile, locator.locateHome()?.canonicalFile)
        } finally {
            userHome.deleteRecursively()
        }
    }

    @Test
    fun `returns null when no candidate exists`() {
        val locator = VfoxHomeLocator(environment = { null }, userHome = "/definitely/not/here")
        assertNull(locator.locateHome())
    }

    @Test
    fun `parses storage sdkPath from config yaml`() {
        val yaml = """
            proxy:
                url: ""
            storage:
                sdkPath: "D:\vfox"
            registry:
                address: ""
        """.trimIndent()
        assertEquals("D:\\vfox", VfoxHomeLocator.parseStorageSdkPath(yaml))
    }

    @Test
    fun `parses unquoted storage sdkPath with forward slashes`() {
        val yaml = "storage:\n    sdkPath: /opt/vfox\n"
        assertEquals("/opt/vfox", VfoxHomeLocator.parseStorageSdkPath(yaml))
    }

    @Test
    fun `ignores a sdkPath key outside the storage block`() {
        val yaml = "other:\n    sdkPath: /should/not/match\n"
        assertNull(VfoxHomeLocator.parseStorageSdkPath(yaml))
    }

    @Test
    fun `returns null when storage block has no sdkPath`() {
        val yaml = "storage:\n    other: 1\n"
        assertNull(VfoxHomeLocator.parseStorageSdkPath(yaml))
    }

    @Test
    fun `skips comments and blank lines`() {
        val yaml = "# comment\nstorage:\n    # inner comment\n    sdkPath: D:/vfox\n"
        assertEquals("D:/vfox", VfoxHomeLocator.parseStorageSdkPath(yaml))
    }
}
