package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import java.util.Base64

/**
 * The commons invite's *text* (spec §7.4): `knit-commons:v1:<base64url, unpadded>` around the 32-byte
 * secret, one line, paste-safe. Mirrors the daemon's `Commons.encodeInvite`/`decodeInvite` byte for byte —
 * the operator mints the string with `knit-spool commons-invite`, so the two decoders must agree on
 * exactly which strings are invites.
 *
 * [decode] is total and returns null for anything that is not an invite: a typo must never become a
 * scope id, since [ScopeCrypto.commonsScopeId] would happily hash it into a subscription to nothing.
 */
object CommonsInvite {
    private const val PREFIX = "knit-commons:v1:"

    /** `knit-commons:v1:<base64url, unpadded>` — the string an operator hands to members. */
    fun encode(secret: ByteArray): String {
        require(secret.size == ScopeCrypto.COMMONS_SECRET_BYTES) { "commons secret must be ${ScopeCrypto.COMMONS_SECRET_BYTES} bytes" }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }

    /** The secret behind an invite, or null when [invite] is not one (wrong prefix, bad base64, wrong length). */
    fun decode(invite: String): ByteArray? {
        val trimmed = invite.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.removePrefix(PREFIX)
        val secret = runCatching { Base64.getUrlDecoder().decode(body) }.getOrNull() ?: return null
        return secret.takeIf { it.size == ScopeCrypto.COMMONS_SECRET_BYTES }
    }

    /** Whether [text] parses as an invite — the relay editor's field validator. */
    fun looksLikeInvite(text: String): Boolean = decode(text) != null
}
