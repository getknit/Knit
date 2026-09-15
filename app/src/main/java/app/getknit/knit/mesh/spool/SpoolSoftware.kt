package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a spool says it is running, read from its `GET /source` document.
 *
 * Deliberately not a HELLO field. The record layer carries version negotiation and nothing else
 * identifying (spec §7.1, B-7.1-5), while every knit-spool already publishes name, version and commit
 * unauthenticated at `/source` — that route is its AGPL §13 source offer, an obligation to the people
 * whose clients talk to it, so it exists on every deployed relay including ones that will never take a
 * new build. Reading what is already there costs one GET and no change to a normative wire.
 *
 * Every field is optional because this document is written by a machine we do not run: a relay may sit
 * behind a proxy that answers `/source` with something else entirely, and an unstamped daemon reports
 * [UNKNOWN_BUILD] for values it was never given. [label] is the only reader, and it renders nothing
 * rather than half a sentence.
 */
@Serializable
class SpoolSoftware(
    val name: String? = null,
    val version: String? = null,
    val commit: String? = null,
) {
    /**
     * One line for the Diagnostics row — `knit-spool 0.3.0 (e8a7790)` — or null when the answer named no
     * software, which is how a `/source` that is not a spool's reaches the screen as no row at all.
     *
     * Each part is bounded and dropped when the daemon reports it [UNKNOWN_BUILD] (the honest answer from
     * an unstamped local build): "knit-spool" alone says what a version string of "unknown" cannot.
     */
    val label: String?
        get() {
            val software = name.stated() ?: return null
            return buildString {
                append(software)
                version.stated()?.let { append(' ').append(it) }
                commit.stated()?.let { append(" (").append(it.shortenIfSha()).append(')') }
            }
        }
}

/**
 * Reads a `/source` body, or null when it is not JSON we understand.
 *
 * Unknown keys are ignored on purpose: the document carries `source` and `license` too, and a future
 * daemon may add more. Kept out of [OkHttpSpoolDialer] so the parse is checkable without a socket.
 */
fun parseSpoolSoftware(body: String): SpoolSoftware? = runCatching { sourceJson.decodeFromString<SpoolSoftware>(body) }.getOrNull()

private val sourceJson = Json { ignoreUnknownKeys = true }

/** What an unstamped daemon reports for a value it was never given (its `BuildInfo.UNKNOWN`). */
private const val UNKNOWN_BUILD = "unknown"

/** A relay we do not run writes this document; one long field must not push a row off the screen. */
private const val MAX_FIELD_CHARS = 32

private const val SHORT_SHA_CHARS = 7

private fun String?.stated(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN_BUILD }?.take(MAX_FIELD_CHARS)

/** A full commit hash is a diagnostics row's whole width, and its first seven name the commit. */
private fun String.shortenIfSha(): String =
    if (length > SHORT_SHA_CHARS && all { it.isDigit() || it in 'a'..'f' }) take(SHORT_SHA_CHARS) else this
