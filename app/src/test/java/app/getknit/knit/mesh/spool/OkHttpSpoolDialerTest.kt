package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions the dialer makes about a dead socket. Both are pure so they can be checked without
 * standing up a server: everything else in that file is OkHttp wiring, which `rules/mesh.md` keeps
 * sealed behind the [SpoolSocket] seam.
 */
class OkHttpSpoolDialerTest {
    @Test
    fun `a refused upgrade is reported by its status, not by the exception that noticed it`() {
        // The case this exists for: a spool at its connection cap answers 503 at the transport layer
        // rather than closing 4003, because §7.1's four close codes have no "come back later" and 4003
        // would accuse a client that did nothing wrong. Losing the status leaves "ProtocolException",
        // which reads exactly like a broken URL.
        assertEquals("http 503", failureReason("ProtocolException", 503))
        assertEquals("http 401", failureReason("ProtocolException", 401))
    }

    @Test
    fun `a socket that never reached HTTP keeps the exception name`() {
        assertEquals("UnknownHostException", failureReason("UnknownHostException", null))
        assertEquals("SSLHandshakeException", failureReason("SSLHandshakeException", null))
    }

    @Test
    fun `a socket that died after a successful upgrade is not reported as an HTTP failure`() {
        // 101 means the upgrade worked and the socket broke later, so the exception is the whole story;
        // "http 101" in the relay row would name a success as the reason for a failure.
        assertEquals("SocketTimeoutException", failureReason("SocketTimeoutException", 101))
    }

    @Test
    fun `Retry-After delta-seconds becomes a millisecond floor`() {
        assertEquals(30_000L, retryAfterMillis("30"))
        assertEquals(1_000L, retryAfterMillis(" 1 "))
    }

    @Test
    fun `an absent, unparseable or non-positive Retry-After asks for nothing`() {
        // Null is "the spool asked for nothing", which leaves the existing backoff untouched. The
        // HTTP-date form lands here on purpose — honouring it would make our backoff a function of the
        // spool's clock against ours.
        assertNull(retryAfterMillis(null))
        assertNull(retryAfterMillis(""))
        assertNull(retryAfterMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(retryAfterMillis("0"))
        assertNull(retryAfterMillis("-30"))
    }

    @Test
    fun `an absurd Retry-After is clamped rather than parking the worker`() {
        // A header is attacker-controlled on a spool the user does not run; an unclamped one would
        // suspend that worker for as long as it liked, and the plane would look simply dead.
        assertEquals(60_000L, retryAfterMillis("86400"))
    }
}
