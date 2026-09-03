package app.getknit.knit.mesh.protocol

/**
 * The filename an arbitrary-file attachment carries, and the rules for trusting one.
 *
 * [app.getknit.knit.mesh.crypto.MessageContent.attachmentName] is open sender-supplied text: it is drawn in
 * a bubble, put in a notification, and offered as the default filename when the recipient saves the file
 * through the storage picker. So it is normalized at the decode boundary — the same posture the reaction
 * set takes toward its open emoji string — rather than anywhere further in, where one missed call site
 * would be the whole gap.
 *
 * [sanitize] is deliberately *not* a validator that rejects: a name we merely disliked would strand an
 * otherwise-fine attachment behind a blank bubble, so it repairs what it can and gives up (null) only on a
 * name with nothing left in it. A null name renders as the file's type instead, which is what an old peer's
 * file already looks like.
 *
 * Pure Kotlin, no Android — JVM-tested in `AttachmentNameTest`.
 */
object AttachmentName {
    /** Longest name kept. Comfortably past any real filename and far short of a bubble-wrecking one. */
    const val MAX_LENGTH = 128

    /**
     * [raw] with everything that could lie about it removed: path separators (so a name can never read as a
     * path, wherever it is later joined), and every control **and format** character, then surrounding
     * whitespace. Both halves of that middle clause earn their place — controls can blank or re-order the
     * line the name is drawn on, and the Unicode format category is where the bidi overrides live, which is
     * the old trick where a U+202E before `fdp.exe` renders the name as `exe.pdf` to a reader about to
     * save it. (Spelled out rather than shown: an override pasted into this comment would flip the comment.)
     *
     * A name longer than [MAX_LENGTH] is truncated **through the stem**, keeping the extension — the
     * extension is the half that tells the user what the file is, and the half the storage picker reads to
     * type the document it creates.
     *
     * Returns null when nothing usable survives, including for the two names that are pure traversal (`.`
     * and `..`).
     */
    fun sanitize(raw: String?): String? {
        val stripped =
            raw
                ?.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT || it in SEPARATORS }
                ?.trim()
                ?: return null
        if (stripped.isEmpty() || stripped == "." || stripped == "..") return null
        return if (stripped.length <= MAX_LENGTH) stripped else truncate(stripped)
    }

    /**
     * [name], shortened to [MAX_LENGTH] with its extension intact. An "extension" here is a short trailing
     * dotted run — a long tail after the last dot is not one, and truncating the head to preserve it would
     * throw away the part of the name that identifies the file.
     */
    private fun truncate(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= MAX_EXTENSION) name.substring(dot) else ""
        return name.take(MAX_LENGTH - ext.length) + ext
    }

    /** Longest trailing `.xxx` run still treated as an extension worth preserving through a truncation. */
    private const val MAX_EXTENSION = 12

    private val SEPARATORS = charArrayOf('/', '\\')
}
