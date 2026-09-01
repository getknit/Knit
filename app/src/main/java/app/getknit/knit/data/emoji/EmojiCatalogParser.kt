package app.getknit.knit.data.emoji

/**
 * Parses the `emoji/emoji_en.tsv` asset (see `scripts/gen-emoji-catalog.py` for the format): blank and `#`
 * lines are skipped; a data line is `emoji ⇥ group-id ⇥ tone(0|1) ⇥ name`. A malformed line — or one whose
 * group id this build doesn't know — is dropped rather than thrown, so a newer asset never crashes an
 * older build; the asset contract test pins "parsed == data lines" so a drop can't ship unnoticed.
 */
object EmojiCatalogParser {
    private const val COLUMNS = 4

    fun parse(lines: Sequence<String>): List<EmojiEntry> =
        lines
            .mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val parts = line.trimEnd('\r').split('\t', limit = COLUMNS)
                if (parts.size != COLUMNS) return@mapNotNull null
                val group = parts[1].toIntOrNull()?.let(EmojiGroup::fromId) ?: return@mapNotNull null
                val emoji = parts[0]
                val name = parts[3]
                if (emoji.isEmpty() || name.isEmpty()) return@mapNotNull null
                EmojiEntry(emoji = emoji, group = group, name = name, toneVariant = parts[2] == "1")
            }.toList()
}
