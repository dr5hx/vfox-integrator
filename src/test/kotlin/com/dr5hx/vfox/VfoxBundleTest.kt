package com.dr5hx.vfox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

/**
 * Guards the two ways a translated string can silently go wrong.
 *
 * The plugin's strings live in a bundle rather than in code so the page follows the IDE's language:
 * a language pack translates the *platform's* bundles and cannot reach a third-party plugin's
 * compiled-in text. That only works if the translation file is complete — a key missing from the
 * Chinese bundle falls back to English with no error — and if it decodes as UTF-8, since a
 * mis-decoded file renders as mojibake rather than failing.
 */
class VfoxBundleTest {

    private val loader = VfoxBundle::class.java.classLoader

    /** The keys a bundle file declares, ignoring comments and blank lines. */
    private fun keysOf(resource: String): Set<String> {
        val text = loader.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$resource is not on the classpath")
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith('!') }
            .mapNotNull { it.substringBefore('=').takeIf { key -> key.isNotBlank() && '=' in it } }
            .map { it.trim() }
            .toSet()
    }

    @Test
    fun `the chinese bundle translates every key the english one declares`() {
        val english = keysOf("messages/VfoxBundle.properties")
        val chinese = keysOf("messages/VfoxBundle_zh_CN.properties")
        // A key present in English but absent here would silently show English in a Chinese IDE,
        // which reads as a half-translated page rather than as a bug.
        assertEquals("keys missing from VfoxBundle_zh_CN.properties", emptySet<String>(), english - chinese)
        assertEquals("keys in the Chinese bundle that English does not declare", emptySet<String>(), chinese - english)
    }

    @Test
    fun `the chinese bundle decodes as utf-8 rather than mojibake`() {
        // Read through the JDK's resource-bundle machinery, which is what the platform's own bundle
        // lookup ultimately uses. Since Java 9 properties files default to UTF-8, so a Chinese value
        // must come back as Chinese; Latin-1 decoding would turn it into Ã¥-style noise.
        val bundle = ResourceBundle.getBundle("messages.VfoxBundle", Locale.SIMPLIFIED_CHINESE, loader)
        val value = bundle.getString("toolwindow.column.version")
        assertEquals("版本", value)
        assertTrue(
            "expected Chinese text, got mojibake: $value",
            value.any { it.code in 0x4E00..0x9FFF },
        )
    }

    @Test
    fun `a parameterised message still formats after translation`() {
        // Placeholders have to survive translation: MessageFormat runs on any message given
        // parameters, so a translator dropping or retyping {0} would print a literal brace.
        val bundle = ResourceBundle.getBundle("messages.VfoxBundle", Locale.SIMPLIFIED_CHINESE, loader)
        val formatted = java.text.MessageFormat.format(bundle.getString("notification.detected.title"), 7)
        assertTrue("placeholder {0} was lost: $formatted", formatted.contains("7"))
    }

    @Test
    fun `the english default is what a bare lookup finds`() {
        // With no locale, the untranslated bundle must still answer every key, so a product whose
        // language has no translation shows English rather than a key name.
        val bundle = ResourceBundle.getBundle("messages.VfoxBundle", Locale.ROOT, loader)
        assertEquals("Rescan", bundle.getString("toolwindow.action.rescan"))
    }
}
