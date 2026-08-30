package app.getknit.knit.mesh.lora

/**
 * How long a draft may be before it risks not fitting the LoRa hop — the composer's "long message" hint
 * (ADR 040). The hop carries at most 3 × 227 = 681 compact bytes (`LoraFrameCodec`); everything above the
 * body — envelope, signature, the X3DH init a DM carries until the peer's first reply — is fixed cost, so a
 * body budget is the honest way to say it before the send. The budgets are deliberately below the true
 * ceilings (a 100-char DM is 439 B with its init; ≈ 335 characters fit) and are pinned by
 * `CoordinationPlaneSizeBudgetTest`, which builds real frames at exactly these sizes and checks they fit in
 * the transcoded `0x05` form the plane sends (ADR 060 — untranscoded, a DM at this budget lands a byte or two
 * over the ceiling), so the hint can under-warn (a longer draft may still fit) but never over-promise. The
 * reserves are what a quoted reply (two ids, an author, a 120-char snippet) and an attachment reference
 * (hash + key + MIME) add inside the same frame. Pure — the composer calls it per keystroke.
 */
object LoraSizeHint {
    /** A plaintext Nearby-room post: no seal, and the codec deflates it, so the budget is the larger one. */
    const val ROOM_BODY_BYTES = 400

    /** A sealed DM, with the session-initial X3DH init still attached (the worst case until the first reply). */
    const val DM_BODY_BYTES = 320

    /** What a quoted reply costs beside the body (`ReplyRef`: message id, author id, name, 120-char snippet). */
    const val REPLY_RESERVE_BYTES = 260

    /** What an attachment reference costs beside the body (content hash, sealed key, MIME). */
    const val ATTACHMENT_RESERVE_BYTES = 170

    /** The body bytes left of [base] once a reply and/or an attachment ride along; never negative. */
    fun budget(
        base: Int,
        replying: Boolean,
        attached: Boolean,
    ): Int =
        (base - (if (replying) REPLY_RESERVE_BYTES else 0) - (if (attached) ATTACHMENT_RESERVE_BYTES else 0))
            .coerceAtLeast(0)

    /** The UTF-8 length of [text] without encoding it (surrogate pairs count 4, a lone surrogate 3). */
    fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes +=
                when {
                    c.code < ONE_BYTE_MAX -> {
                        1
                    }

                    c.code < TWO_BYTE_MAX -> {
                        2
                    }

                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        i++
                        PAIR_BYTES
                    }

                    else -> {
                        BMP_BYTES
                    }
                }
            i++
        }
        return bytes
    }

    private const val ONE_BYTE_MAX = 0x80
    private const val TWO_BYTE_MAX = 0x800

    /** A supplementary code point (a surrogate pair) encodes as four bytes; any other char above U+07FF as three. */
    private const val PAIR_BYTES = 4
    private const val BMP_BYTES = 3
}
