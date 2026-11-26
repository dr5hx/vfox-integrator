package com.dr5hx.vfox.ui

import com.dr5hx.vfox.model.VfoxSdkVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the summary text a balloon carries.
 *
 * The balloon itself needs a running IDE to show, so only the part that decides *what* to say is
 * covered here — which is also where the interesting behaviour lives (grouping, ordering, and
 * staying silent when there is nothing to report).
 */
class VfoxNotifierTest {

    private fun version(sdk: String, v: String) = VfoxSdkVersion(sdk = sdk, version = v)

    @Test
    fun `says nothing when no versions were found`() {
        assertNull(VfoxNotifier.scanSummary(emptyList()))
    }

    @Test
    fun `groups versions by sdk with a count`() {
        val summary = VfoxNotifier.scanSummary(
            listOf(
                version("java", "21.0.2"),
                version("java", "17.0.2"),
                version("nodejs", "24.14.0"),
            ),
        )
        assertEquals("java (2), nodejs (1)", summary)
    }

    @Test
    fun `orders sdks alphabetically regardless of input order`() {
        val summary = VfoxNotifier.scanSummary(
            listOf(
                version("python", "3.12.0"),
                version("nodejs", "24.14.0"),
                version("java", "21.0.2"),
            ),
        )
        assertEquals("java (1), nodejs (1), python (1)", summary)
    }

    @Test
    fun `counts a single sdk`() {
        val summary = VfoxNotifier.scanSummary(listOf(version("golang", "1.27.1")))
        assertEquals("golang (1)", summary)
    }

    @Test
    fun `summary never names a home path`() {
        // The balloon is short by design; paths belong in the tool window and the log file.
        val summary = VfoxNotifier.scanSummary(
            listOf(VfoxSdkVersion(sdk = "java", version = "21.0.2", home = "D:/vfox/cache/java/v-21.0.2/x")),
        )
        assertTrue(summary!!.isNotBlank())
        assertTrue("summary should not leak a home path: $summary", !summary.contains("vfox/cache"))
    }
}
