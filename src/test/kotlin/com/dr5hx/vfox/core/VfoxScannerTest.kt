package com.dr5hx.vfox.core

import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VfoxScannerTest {

    /**
     * Builds a `vfox info <sdk>@<version> --json` document.
     *
     * Serialization is done here rather than with hand-written literals because vfox emits Windows
     * paths, whose backslashes must be doubled in JSON — exactly the kind of fixture that silently
     * drifts from the real output when written by hand.
     */
    private fun infoJson(
        name: String,
        version: String,
        home: String,
        paths: List<String>,
        variables: Map<String, String> = emptyMap(),
    ): String {
        val env = linkedMapOf<String, Any>(
            "paths" to paths,
            "variables" to variables,
        )
        val root = linkedMapOf<String, Any>(
            "name" to name,
            "version" to version,
            "installed" to true,
            "home" to home,
            "env" to env,
        )
        return serialize(root)
    }

    /** Minimal JSON serializer for the map/list/string/boolean values used by these fixtures. */
    private fun serialize(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k.toString())}:${serialize(v)}" }
        is List<*> -> value.joinToString(",", "[", "]") { serialize(it) }
        else -> quote(value.toString())
    }

    /** Escapes [value] as a JSON string literal. */
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    @Test
    fun `parses a node info document`() {
        val home = "D:\\vfox\\cache\\nodejs\\v-20.20.2\\nodejs-20.20.2"
        val parsed = VfoxScanner.parseInfo("nodejs", "20.20.2", infoJson("nodejs", "20.20.2", home, listOf(home)))
        assertEquals("nodejs", parsed?.sdk)
        assertEquals("20.20.2", parsed?.version)
        assertEquals(home, parsed?.home)
        assertEquals(listOf(home), parsed?.paths)
        assertEquals(emptyMap<String, String>(), parsed?.variables)
    }

    @Test
    fun `parses java info variables`() {
        val home = "D:\\vfox\\cache\\java\\v-21.0.2+14-librca\\java-21.0.2+14-librca"
        val json = infoJson(
            name = "java",
            version = "21.0.2+14-librca",
            home = home,
            paths = listOf("$home\\bin"),
            variables = mapOf("JAVA_HOME" to home),
        )
        val parsed = VfoxScanner.parseInfo("java", "21.0.2+14-librca", json)
        assertEquals("21.0.2+14-librca", parsed?.version)
        assertEquals(home, parsed?.home)
        assertEquals(mapOf("JAVA_HOME" to home), parsed?.variables)
    }

    @Test
    fun `parses golang info with two variables`() {
        val home = "D:\\vfox\\golang\\v-1.27.1\\golang-1.27.1"
        val json = infoJson(
            name = "golang",
            version = "1.27.1",
            home = home,
            paths = listOf("$home\\bin", "$home\\packages\\bin"),
            variables = mapOf("GOROOT" to home, "GOPATH" to "$home/packages"),
        )
        val parsed = VfoxScanner.parseInfo("golang", "1.27.1", json)
        assertEquals(home, parsed?.variables?.get("GOROOT"))
        assertEquals("$home/packages", parsed?.variables?.get("GOPATH"))
    }

    @Test
    fun `returns null for a non-json error response`() {
        assertNull(VfoxScanner.parseInfo("nodejs", "9.9.9", "Error: version not found"))
    }

    @Test
    fun `returns null when the home key is missing`() {
        val json = serialize(linkedMapOf<String, Any>("name" to "nodejs", "version" to "1.0.0"))
        assertNull(VfoxScanner.parseInfo("nodejs", "1.0.0", json))
    }

    @Test
    fun `orders versions newest first numerically`() {
        val versions = listOf("14.21.3", "9.0.0", "20.20.2", "17.9.1", "18.20.8")
        val sorted = versions.sortedWith { a, b -> VfoxScanner.compareVersions(b, a) }
        assertEquals(listOf("20.20.2", "18.20.8", "17.9.1", "14.21.3", "9.0.0"), sorted)
    }

    @Test
    fun `orders versions with build metadata and vendor suffixes`() {
        val versions = listOf("8.0.342+7-librca", "21.0.2+14-librca", "11.0.16+8-librca")
        val sorted = versions.sortedWith { a, b -> VfoxScanner.compareVersions(b, a) }
        assertEquals(listOf("21.0.2+14-librca", "11.0.16+8-librca", "8.0.342+7-librca"), sorted)
    }

    @Test
    fun `compares versions of differing segment counts`() {
        assertEquals(-1, VfoxScanner.compareVersions("1.27", "1.27.1"))
        assertEquals(0, VfoxScanner.compareVersions("1.27.1", "1.27.1"))
    }

    @Test
    fun `display name is prefixed and stable`() {
        val version = VfoxSdkVersion(sdk = "nodejs", version = "24.14.0")
        assertEquals("vfox-nodejs-24.14.0", version.displayName)
    }
}
