package app.getknit.knit.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * The field ids below are copied from `.github/ISSUE_TEMPLATE/bug_report.yml`. That file and
 * `CrashIssueUrl` are a contract; renaming a field there should break this test rather than silently
 * ship a form that prefills nothing.
 */
class CrashIssueUrlTest {
    private val base = "https://github.com/getknit/knit/issues/new"

    private val ref =
        CrashReportRef(
            at = 1_700_000_000_000L,
            summary = "IllegalStateException at BluetoothMeshTransport.kt:552",
            appVersion = "2.3.0 (13) release",
            device = "Google Pixel 8 (shiba)",
            androidVersion = "16 (SDK 36)",
            file = File("crash-1700000000000-deadbeef.txt"),
        )

    private fun url(text: String = SHORT_TRACE) = CrashIssueUrl.forReport(base, ref, text)

    private fun params(url: String): Map<String, String> =
        url
            .substringAfter('?')
            .split('&')
            .associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
            }

    @Test
    fun `targets the bug report template`() {
        assertEquals("bug_report.yml", params(url())["template"])
        assertTrue(url().startsWith("$base?"))
    }

    @Test
    fun `prefills everything the app can know`() {
        val fields = params(url())
        assertEquals("2.3.0 (13) release", fields["version"])
        assertEquals("Google Pixel 8 (shiba), 16 (SDK 36)", fields["devices"])
        assertTrue(fields.getValue("title").startsWith("[Bug]: Crash - IllegalStateException"))
        assertTrue(fields.getValue("logs").contains(SHORT_TRACE))
    }

    @Test
    fun `leaves steps and expected for the human`() {
        // Both are required:true in the template, so GitHub blocks submission until they are written.
        // That is the whole point: it is the gap the needs-info bot flagged on issue #9.
        val fields = params(url())
        assertFalse(fields.containsKey("steps"))
        assertFalse(fields.containsKey("expected"))
    }

    @Test
    fun `carries the triage marker`() {
        assertTrue(params(url()).getValue("logs").contains(CrashIssueUrl.MARKER))
    }

    @Test
    fun `radios is one of the template's dropdown options`() {
        val options = setOf("Not sure", "Wi-Fi Aware (NAN)", "Bluetooth LE", "Both")
        assertTrue(params(url())["radios"] in options)
    }

    @Test
    fun `encodes spaces as percent-twenty, never plus`() {
        val raw = url()
        assertTrue(raw.contains("%20"))
        assertFalse(raw.substringAfter('?').contains('+'))
    }

    @Test
    fun `drops blank fields rather than sending them empty`() {
        val blank = CrashIssueUrl.forReport(base, ref.copy(appVersion = "", device = "", androidVersion = ""), SHORT_TRACE)
        assertFalse(params(blank).containsKey("version"))
        assertFalse(params(blank).containsKey("devices"))
    }

    @Test
    fun `a short trace is carried whole and unmarked`() {
        val logs = params(url()).getValue("logs")
        assertTrue(logs.contains(SHORT_TRACE))
        assertFalse(logs.contains("truncated"))
    }

    @Test
    fun `a huge trace is budgeted against its encoded length`() {
        val huge = (1..4_000).joinToString("\n") { "\tat app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:$it)" }
        val built = url(huge)
        assertTrue(built.length <= CrashIssueUrl.MAX_URL_CHARS)
        val logs = params(built).getValue("logs")
        assertTrue(logs.contains("MeshRouter.kt:1)"))
        assertTrue(logs.contains("truncated"))
    }

    @Test
    fun `a raw-length budget would have overshot`() {
        // Pins the reason the budget measures encoded characters: newlines and tabs each become three.
        val huge = (1..4_000).joinToString("\n") { "\tat app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:$it)" }
        val logs = params(url(huge)).getValue("logs")
        assertTrue(logs.length < CrashIssueUrl.MAX_URL_CHARS)
    }

    private companion object {
        const val SHORT_TRACE = "java.lang.IllegalStateException: boom"
    }
}
