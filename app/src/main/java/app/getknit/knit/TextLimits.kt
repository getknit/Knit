package app.getknit.knit

/**
 * Character limits for user-editable free-text inputs, shared by the input fields that enforce them,
 * their on-screen counters, and the wire-level guards in [app.getknit.knit.mesh.MeshManager].
 * Centralized so the cap and the counter can never drift apart.
 */
object TextLimits {
    /** Profile display name — single line, shown in chat lists, headers, and notifications. */
    const val DISPLAY_NAME = 32

    /** Profile status one-liner. */
    const val STATUS = 100

    /** Group title — single line, shown in the chat header. */
    const val GROUP_NAME = 32

    /** Chat message body. Generous, but bounded so a frame stays well within the transport's payload budget. */
    const val MESSAGE = 2000

    /**
     * A reaction emoji, in UTF-16 units — the unit every other cap here is enforced in. The longest RGI emoji
     * sequences are 15 units (a two-person kiss with skin tones) and 14 (a tag-sequence subdivision flag), so
     * 32 is ~2× the worst case with room for Unicode to grow a sequence, while bounding the UTF-8 form at
     * 64 B — the whole of a sealed reaction's size variance on the wire. Enforced on both send and receive by
     * [isValidReactionEmoji]; a length cap only, never an emoji-class test (see that function).
     */
    const val REACTION = 32
}

/**
 * Whether [emoji] is a reaction the wire accepts: non-blank and at most [TextLimits.REACTION] UTF-16 units.
 * Shared by the sender ([app.getknit.knit.mesh.MeshManager.sendReaction]) and both inbound paths so the
 * two can never drift. Deliberately **length-only**: an emoji-class check (code-point ranges, "one grapheme
 * cluster") would make an old build drop every emoji Unicode adds after it shipped, and grapheme
 * segmentation varies with the device ICU — the picker is the only emitter and is where "one RGI emoji"
 * is guaranteed. Blank is refused rather than read as a retraction: retraction is the explicit `null`.
 */
fun isValidReactionEmoji(emoji: String): Boolean = emoji.isNotBlank() && emoji.length <= TextLimits.REACTION

/**
 * Normalizes a single-line field: trims the ends and collapses internal whitespace runs (including
 * stray newlines/tabs from a paste) down to single spaces. Apply this at commit time, never
 * per-keystroke — trimming the trailing space on every keystroke would stop the user from typing a
 * space between words (the field would reset before the next character).
 */
fun normalizeSingleLine(value: String): String = value.trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")
