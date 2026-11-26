package com.dr5hx.vfox.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the catalogue's shape.
 *
 * The catalogue is data, so the interesting failures are structural: a plugin listed twice, a
 * candidate row with no `SdkType` to resolve, an unknown plugin silently dropped. Each is a test
 * here rather than a runtime surprise.
 */
class VfoxSdkCatalogTest {

    @Test
    fun `every plugin is named once`() {
        val plugins = VfoxSdkCatalog.entries.map { it.plugin }
        assertEquals("duplicate plugin in the catalogue: $plugins", plugins.size, plugins.toSet().size)
    }

    @Test
    fun `every sdk candidate names at least one SdkType`() {
        for (entry in VfoxSdkCatalog.entries) {
            if (entry.support == VfoxSupport.SDK_CANDIDATE) {
                assertTrue(
                    "${entry.plugin} is an SDK candidate but names no SdkType",
                    entry.sdkTypes.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `only sdk candidates name SdkTypes`() {
        for (entry in VfoxSdkCatalog.entries) {
            if (entry.support != VfoxSupport.SDK_CANDIDATE) {
                assertTrue(
                    "${entry.plugin} is ${entry.support} but names SdkTypes, which it would never use",
                    entry.sdkTypes.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `sdk type names are fully qualified`() {
        for (entry in VfoxSdkCatalog.entries) {
            for (name in entry.sdkTypes) {
                assertTrue("not fully qualified: $name", name.contains('.'))
                assertTrue("not a class name: $name", name.substringAfterLast('.').first().isUpperCase())
            }
        }
    }

    @Test
    fun `java only targets the IDEA SDK type`() {
        // Listing the platform's SimpleJavaSdkType here would register JDK entries in products with
        // no Java support at all (WebStorm, DataGrip); the integration is IDEA-only by design.
        val java = VfoxSdkCatalog.entryFor("java")
        assertNotNull(java)
        assertEquals(1, java!!.sdkTypes.size)
        assertTrue(java.sdkTypes.single().endsWith("JavaSdkImpl"))
    }

    @Test
    fun `a plugin the catalogue knows keeps its level`() {
        assertEquals(VfoxSupport.SDK_CANDIDATE, VfoxSdkCatalog.supportFor("java"))
        assertEquals(VfoxSupport.DEDICATED, VfoxSdkCatalog.supportFor("golang"))
        assertEquals(VfoxSupport.DEDICATED, VfoxSdkCatalog.supportFor("nodejs"))
        assertEquals(VfoxSupport.BUILD_TOOL, VfoxSdkCatalog.supportFor("maven"))
    }

    @Test
    fun `an unknown plugin is env-only rather than dropped`() {
        // A vfox plugin newer than this table must still be listed and exported, not ignored.
        assertEquals(VfoxSupport.ENV_ONLY, VfoxSdkCatalog.supportFor("some-future-sdk"))
        assertNull(VfoxSdkCatalog.entryFor("some-future-sdk"))
    }

    @Test
    fun `an unknown plugin falls back to its own name for a title`() {
        assertEquals("some-future-sdk", VfoxSdkCatalog.titleFor("some-future-sdk"))
    }

    @Test
    fun `registrable plugins are exactly the sdk and dedicated ones`() {
        val registrable = VfoxSdkCatalog.registrablePlugins().toSet()
        val expected = VfoxSdkCatalog.entries
            .filter { it.support == VfoxSupport.SDK_CANDIDATE || it.support == VfoxSupport.DEDICATED }
            .map { it.plugin }
            .toSet()
        assertEquals(expected, registrable)
        assertTrue("build tools are not SDK candidates", "maven" !in registrable)
        assertTrue("env-only SDKs are not SDK candidates", "rust" !in registrable)
    }
}
