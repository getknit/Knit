package app.getknit.knit.data.emoji

/**
 * One catalog row: a fully-qualified RGI [emoji], its [group], its English CLDR short [name] (the search
 * key and the accessibility label), and whether it is a skin-tone variant of another entry ([toneVariant] —
 * hidden from the browse grid, still reachable by search).
 */
data class EmojiEntry(
    val emoji: String,
    val group: EmojiGroup,
    val name: String,
    val toneVariant: Boolean,
) {
    /** Lower-cased [name], computed once so search never re-folds 4,000 strings per keystroke. */
    val searchKey: String = name.lowercase()
}
