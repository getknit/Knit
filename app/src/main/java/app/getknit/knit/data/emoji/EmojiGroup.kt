package app.getknit.knit.data.emoji

/**
 * The nine Unicode emoji groups, in Unicode's own order. The ordinal **is** the group-id column of the
 * `emoji/emoji_en.tsv` asset (`GROUPS` in `scripts/gen-emoji-catalog.py`) — grow the two together. The
 * generator hard-fails on a group it doesn't know, so a Unicode release that adds one can't drift past this
 * enum silently; the parser drops a line whose id is out of range, and the asset contract test catches that.
 */
enum class EmojiGroup {
    SMILEYS,
    PEOPLE,
    ANIMALS,
    FOOD,
    TRAVEL,
    ACTIVITIES,
    OBJECTS,
    SYMBOLS,
    FLAGS,
    ;

    companion object {
        /** The group with this asset id, or null when the id is outside what this build knows. */
        fun fromId(id: Int): EmojiGroup? = entries.getOrNull(id)
    }
}
