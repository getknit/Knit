package app.getknit.knit.mesh.spool

/**
 * The pure rules about a spool URL's *text*, shared by the dialer and the settings UI.
 *
 * [isAcceptable] exists here rather than inside [OkHttpSpoolDialer] because it is a security rule with
 * two callers: the dialer refuses a bad scheme at dial time (ADR 019 — a release APK must not be
 * pointable at a plaintext relay however its settings are edited), and the relay editor refuses to
 * *store* one so the user is told at the point of entry instead of watching a row sit unreachable
 * forever. Two copies of that rule would eventually disagree, and the copy that drifts is the one that
 * lets a `ws://` URL into a release build.
 */
object SpoolUrl {
    private const val WSS_SCHEME = "wss://"
    private const val WS_SCHEME = "ws://"

    /** The record layer's own path (spec B-7.1-1), which a URL carries and the HTTP routes beside it do not. */
    private const val SPOOL_PATH = "/spool/v1"

    /** Whether this URL's scheme is usable in this build. `wss://` always; `ws://` only when [allowCleartext]. */
    fun isAcceptable(
        url: String,
        allowCleartext: Boolean,
    ): Boolean = url.startsWith(WSS_SCHEME) || (allowCleartext && url.startsWith(WS_SCHEME))

    /**
     * Strips any `?k=` bearer token. Every rendering of a spool URL — a log line, a settings row, a
     * diagnostics dump — goes through this: the token is the whole access control for a private spool
     * (spec §7.1), so it must not reach a screenshot, a bug report, or logcat.
     */
    fun redact(url: String): String = url.substringBefore('?')

    /**
     * The host[:port] for a compact settings row, with the scheme, path and token dropped. Falls back to
     * the redacted URL when the shape is unexpected, so a row never renders empty.
     */
    fun host(url: String): String {
        val withoutToken = redact(url)
        val afterScheme = withoutToken.substringAfter("://", missingDelimiterValue = "")
        val authority = afterScheme.substringBefore('/')
        return authority.ifEmpty { withoutToken }
    }

    /**
     * Where this spool publishes what it is running: its `GET /source` document, derived from the URL we
     * dial by swapping the scheme and replacing [SPOOL_PATH]. Null when the URL is not one this build
     * would dial at all, so the plaintext rule [isAcceptable] enforces covers this fetch too — a release
     * APK must no more read a relay's build over cleartext than sync with it.
     *
     * The path is replaced rather than the whole URL discarded because the daemon serves `/source` beside
     * the socket: a relay mounted at a proxy prefix answers at that prefix, and only a URL that does not
     * carry the spec'd path falls back to the origin.
     */
    fun sourceUrl(
        url: String,
        allowCleartext: Boolean,
    ): String? {
        if (!isAcceptable(url, allowCleartext)) return null
        val withoutToken = redact(url)
        val scheme = if (withoutToken.startsWith(WSS_SCHEME)) "https://" else "http://"
        val afterScheme = withoutToken.substringAfter("://", missingDelimiterValue = "")
        val authority = afterScheme.substringBefore('/').ifEmpty { return null }
        val path = afterScheme.removePrefix(authority).trimEnd('/')
        val mountedAt = if (path.endsWith(SPOOL_PATH)) path.removeSuffix(SPOOL_PATH) else ""
        return "$scheme$authority$mountedAt/source"
    }
}
