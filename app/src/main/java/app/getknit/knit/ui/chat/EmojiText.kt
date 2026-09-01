@file:Suppress("MatchingDeclarationName") // pure emoji-detection helper, colocated with MentionText

package app.getknit.knit.ui.chat

import java.text.BreakIterator

/** Max emoji in an emoji-only body still rendered enlarged, Signal-style ("jumbomoji"). */
const val JUMBO_EMOJI_MAX = 5

private const val ZWJ = 0x200D // zero-width joiner (glues a ZWJ emoji sequence into one grapheme)
private const val VS15 = 0xFE0E // variation selector-15: force text presentation
private const val VS16 = 0xFE0F // variation selector-16: force emoji presentation
private const val KEYCAP = 0x20E3 // combining enclosing keycap (e.g. "1️⃣")

/**
 * The number of emoji in [body] when it consists ONLY of emoji — surrounding and inter-emoji
 * whitespace ignored — otherwise 0. Used to render a short emoji-only message larger, like Signal's
 * "jumbomoji". Returns 0 once the count passes [JUMBO_EMOJI_MAX], so a long wall of emoji renders at
 * normal size (and the scan short-circuits).
 *
 * Splits [body] into extended grapheme clusters ([BreakIterator], as `avatarInitial` does) so a
 * ZWJ/skin-tone/flag sequence counts as one emoji, then classifies each cluster by Unicode emoji
 * code-point ranges. Deliberately avoids `Character.isEmoji*` (added only at API 36; minSdk is 29) so
 * it behaves identically on every supported device and on the host JVM. Pure — no Android deps.
 *
 * The ranges are pinned against the shipped emoji catalog (`EmojiCatalogAssetTest`: every catalog entry
 * must count as exactly one), so a Unicode bump that lands outside them fails a test rather than
 * quietly rendering a lone new emoji at body size.
 */
fun emojiOnlyCount(body: String): Int {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return 0
    val boundary = BreakIterator.getCharacterInstance().apply { setText(trimmed) }
    var start = boundary.first()
    var end = boundary.next()
    var count = 0
    while (end != BreakIterator.DONE) {
        val cluster = trimmed.substring(start, end)
        start = end
        end = boundary.next()
        if (cluster.isBlank()) continue // whitespace between/around emoji doesn't disqualify
        if (!isEmojiCluster(cluster)) return 0
        count++
        if (count > JUMBO_EMOJI_MAX) return 0
    }
    return count
}

/**
 * True when [cluster] (a single grapheme) renders as emoji: it "wants" emoji presentation AND every
 * code point is emoji-related. The all-emoji-related guard rejects bare digits / `#` / `*`, which are
 * emoji-related only as keycap bases and are plain text on their own.
 */
private fun isEmojiCluster(cluster: String): Boolean {
    val codePoints = cluster.codePoints().toArray()
    if (codePoints.isEmpty()) return false
    var wantsEmoji = false
    for (cp in codePoints) {
        if (!isEmojiRelated(cp)) return false
        if (forcesEmojiPresentation(cp)) wantsEmoji = true
    }
    return wantsEmoji
}

/** True for an emoji code point or one of the joiners/selectors/bases that legitimately appear in an
 *  emoji grapheme cluster (so a non-emoji code point in the cluster disqualifies the whole message). */
private fun isEmojiRelated(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF || // SMP emoji blocks (emoticons, pictographs, transport, flags, skin tones…)
        cp in 0x2600..0x27BF || // Misc Symbols + Dingbats (☀ ✂ ✅ …)
        cp in 0x2300..0x23FF || // Misc Technical emoji (⌚ ⌛ ⏰ ⏳ …)
        cp in 0x2B00..0x2BFF || // geometric shapes / stars (⭐ ⬛ ⬜ …)
        cp in 0xE0020..0xE007F || // tag characters: the subdivision flags (🏴󠁧󠁢󠁥󠁮󠁧󠁿 🏴󠁧󠁢󠁳󠁣󠁴󠁿 🏴󠁧󠁢󠁷󠁬󠁳󠁿) are 🏴 + tags
        isScatteredBmpEmoji(cp) ||
        cp == ZWJ ||
        cp == VS15 ||
        cp == VS16 ||
        cp == KEYCAP ||
        isKeycapBase(cp)

/** The BMP emoji outside the four blocks above — ©️ ®️ ‼️ ⁉️ ™️ ℹ️, the arrows, Ⓜ️, the small squares and
 *  play/reverse triangles, 〰️ 〽️ ㊗️ ㊙️ — all text-presentation by default (emoji only with VS16). */
private fun isScatteredBmpEmoji(cp: Int): Boolean = SCATTERED_BMP_EMOJI.any { cp in it }

/** True when [cp] forces or defaults to emoji (not text) presentation: an SMP-emoji code point
 *  (default emoji presentation for the common blocks), VS16, a keycap combiner, or one of the BMP
 *  symbols Unicode gives `Emoji_Presentation=Yes` ([isBmpEmojiPresentation]). Any other bare BMP symbol
 *  (e.g. `☀`, `©`) only counts as emoji when its cluster carries VS16. */
private fun forcesEmojiPresentation(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp == VS16 || cp == KEYCAP || isBmpEmojiPresentation(cp)

/** The BMP code points with `Emoji_Presentation=Yes` (fully-qualified without VS16 in emoji-test.txt):
 *  ⌚ ⌛ ⏩‥⏬ ⏰ ⏳ ◽ ◾ ☔ ☕ ♈‥♓ ♿ ⚓ ⚡ ⚪ ⚫ ⚽ ⚾ ⛄ ⛅ ⛎ ⛔ ⛪ ⛲ ⛳ ⛵ ⛺ ⛽ ✅ ✊ ✋ ✨ ❌ ❎ ❓‥❕ ❗ ➕‥➗ ➰ ➿ ⬛ ⬜ ⭐ ⭕. */
private fun isBmpEmojiPresentation(cp: Int): Boolean = BMP_EMOJI_PRESENTATION.any { cp in it }

/** Code-point ranges behind [isScatteredBmpEmoji] (a single code point is `x..x`), in code-point order. */
private val SCATTERED_BMP_EMOJI: List<IntRange> =
    listOf(
        0x00A9..0x00A9,
        0x00AE..0x00AE,
        0x203C..0x203C,
        0x2049..0x2049,
        0x2122..0x2122,
        0x2139..0x2139,
        0x2194..0x2199,
        0x21A9..0x21AA,
        0x24C2..0x24C2,
        0x25AA..0x25AB,
        0x25B6..0x25B6,
        0x25C0..0x25C0,
        0x25FB..0x25FE,
        0x2934..0x2935,
        0x3030..0x3030,
        0x303D..0x303D,
        0x3297..0x3297,
        0x3299..0x3299,
    )

/** Code-point ranges behind [isBmpEmojiPresentation], in code-point order. */
private val BMP_EMOJI_PRESENTATION: List<IntRange> =
    listOf(
        0x231A..0x231B,
        0x23E9..0x23EC,
        0x23F0..0x23F0,
        0x23F3..0x23F3,
        0x25FD..0x25FE,
        0x2614..0x2615,
        0x2648..0x2653,
        0x267F..0x267F,
        0x2693..0x2693,
        0x26A1..0x26A1,
        0x26AA..0x26AB,
        0x26BD..0x26BE,
        0x26C4..0x26C5,
        0x26CE..0x26CE,
        0x26D4..0x26D4,
        0x26EA..0x26EA,
        0x26F2..0x26F3,
        0x26F5..0x26F5,
        0x26FA..0x26FA,
        0x26FD..0x26FD,
        0x2705..0x2705,
        0x270A..0x270B,
        0x2728..0x2728,
        0x274C..0x274C,
        0x274E..0x274E,
        0x2753..0x2755,
        0x2757..0x2757,
        0x2795..0x2797,
        0x27B0..0x27B0,
        0x27BF..0x27BF,
        0x2B1B..0x2B1C,
        0x2B50..0x2B50,
        0x2B55..0x2B55,
    )

/** The keycap bases `0`-`9`, `#`, `*` — emoji only inside a keycap sequence (base + VS16 + [KEYCAP]). */
private fun isKeycapBase(cp: Int): Boolean = cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code
