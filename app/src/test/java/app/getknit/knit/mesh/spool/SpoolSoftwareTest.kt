package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure halves of the Diagnostics build row: where the document is fetched from, and what a
 * relay's answer renders as. The GET between them is OkHttp wiring behind the [SpoolDialer] seam.
 */
class SpoolSoftwareTest {
    @Test
    fun `the source URL swaps the scheme, drops the token and replaces the record path`() {
        // The token is the whole access control for a private spool (§7.1) and `/source` is deliberately
        // unauthenticated, so sending it would put the credential in a proxy log to read a public document.
        assertEquals(
            "https://relay.example.org/source",
            SpoolUrl.sourceUrl("wss://relay.example.org/spool/v1?k=secret-token", allowCleartext = false),
        )
    }

    @Test
    fun `a spool behind a path prefix is asked at that prefix`() {
        // The daemon serves /source beside the socket, so a reverse proxy that mounts it under a prefix
        // answers there too; going to the origin would 404 against exactly the deployments that use one.
        assertEquals(
            "https://example.org/knit/source",
            SpoolUrl.sourceUrl("wss://example.org/knit/spool/v1", allowCleartext = false),
        )
        assertEquals(
            "https://example.org/source",
            SpoolUrl.sourceUrl("wss://example.org/spool/v1/", allowCleartext = false),
        )
        // A URL that does not carry the spec'd path is a shape we cannot reason about: ask the origin.
        assertEquals("https://example.org/source", SpoolUrl.sourceUrl("wss://example.org", allowCleartext = false))
    }

    @Test
    fun `a release build reads a relay's build over TLS or not at all`() {
        // The same rule the dialer enforces on the socket (ADR 019): a release APK pointed at a plaintext
        // relay must not quietly issue a plaintext request either.
        assertNull(SpoolUrl.sourceUrl("ws://10.0.0.5:8080/spool/v1", allowCleartext = false))
        assertEquals(
            "http://10.0.0.5:8080/source",
            SpoolUrl.sourceUrl("ws://10.0.0.5:8080/spool/v1", allowCleartext = true),
        )
        assertNull(SpoolUrl.sourceUrl("https://relay.example.org/spool/v1", allowCleartext = true))
    }

    @Test
    fun `a stamped daemon renders as name, version and short commit`() {
        val doc =
            """{"name":"knit-spool","version":"0.3.0","commit":"e8a7790",""" +
                """"source":"https://github.com/getknit/knit-spool","license":"AGPL-3.0-or-later"}"""
        assertEquals("knit-spool 0.3.0 (e8a7790)", parseSpoolSoftware(doc)?.label)
    }

    @Test
    fun `a full commit hash is shortened rather than filling the row`() {
        val doc = """{"name":"knit-spool","version":"1.0.0","commit":"e8a7790ab1cd34ef5678901234567890abcdef12"}"""
        assertEquals("knit-spool 1.0.0 (e8a7790)", parseSpoolSoftware(doc)?.label)
    }

    @Test
    fun `an unstamped build says what it is and claims no version`() {
        // `./gradlew :daemon:run` reports "unknown" for both, which is the daemon being honest. Rendering
        // it verbatim would read as a version string nobody can act on.
        val doc = """{"name":"knit-spool","version":"unknown","commit":"unknown"}"""
        assertEquals("knit-spool", parseSpoolSoftware(doc)?.label)
    }

    @Test
    fun `an answer that names no software shows no row`() {
        // What a proxy, a captive portal or a non-knit spool implementation answers on that path. Null is
        // the row not appearing, never "unknown" beside a perfectly healthy relay.
        assertNull(parseSpoolSoftware("""{"status":"ok"}""")?.label)
        assertNull(parseSpoolSoftware("""{"name":"  "}""")?.label)
        assertNull(parseSpoolSoftware("<html><body>404</body></html>"))
        assertNull(parseSpoolSoftware(""))
    }

    @Test
    fun `an overlong field is bounded, because a relay we do not run writes it`() {
        val doc = """{"name":"${"n".repeat(200)}","version":"${"9".repeat(200)}"}"""
        val label = parseSpoolSoftware(doc)?.label
        assertEquals("${"n".repeat(32)} ${"9".repeat(32)}", label)
    }
}
