package app.getknit.knit

import app.getknit.knit.identity.NameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NameKeyTest {
    @Test
    fun foldsCaseAndTrims() {
        assertEquals("alice", NameKey.of("Alice"))
        assertEquals("alice", NameKey.of("  ALICE "))
        assertEquals("", NameKey.of("   "))
    }

    @Test
    fun collapsesWhitespaceRunsIncludingUnicodeSpaces() {
        assertEquals("alice smith", NameKey.of("Alice \t  Smith"))
        // NBSP, em space and the ideographic space are NFKC-compatible with a plain space.
        assertEquals("alice smith", NameKey.of("Alice\u00A0Smith"))
        assertEquals("alice smith", NameKey.of("Alice\u2003Smith"))
        assertEquals("alice smith", NameKey.of("Alice\u3000Smith"))
    }

    @Test
    fun normalizesCompatibilityFormsNfkc() {
        assertEquals("alice", NameKey.of("\uFF21\uFF4C\uFF49\uFF43\uFF45")) // fullwidth "Ａｌｉｃｅ"
        assertEquals("fish", NameKey.of("\uFB01sh")) // the "ﬁ" ligature
        assertEquals("\u00E9", NameKey.of("e\u0301")) // decomposed é composes
    }

    @Test
    fun stripsFormatCharacters() {
        assertEquals("alice", NameKey.of("Al\u200Bice")) // zero-width space
        assertEquals("alice", NameKey.of("Al\u200Dice")) // zero-width joiner
        assertEquals("alice", NameKey.of("\u202EAlice")) // right-to-left override
        assertEquals("alice", NameKey.of("\uFEFFAlice")) // byte-order mark
        assertEquals("alice", NameKey.of("Alice\u200E")) // left-to-right mark
    }

    @Test
    fun doesNotFoldConfusablesByDesign() {
        // A recorded limit: homoglyph folding is deliberately out of scope, so a Cyrillic "а" stays distinct.
        assertNotEquals(NameKey.of("alice"), NameKey.of("\u0430lice"))
    }
}
