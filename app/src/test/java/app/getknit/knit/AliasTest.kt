package app.getknit.knit

import app.getknit.knit.identity.ALIAS_ADJECTIVES
import app.getknit.knit.identity.ALIAS_ADVERBS
import app.getknit.knit.identity.ALIAS_NOUNS
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.displayNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class AliasTest {
    private val token = Regex("^([A-Z][a-z]+){3}$")
    private val phrase = Regex("^([A-Z][a-z]+){3}( ([A-Z][a-z]+){3})*$")

    private fun randomNodeId(rng: Random): String = (1..8).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")

    @Test
    fun aliasIsDeterministic() {
        // Same id -> same alias on every call (and therefore on every device).
        repeat(50) {
            val id = randomNodeId(Random(it.toLong()))
            assertEquals(Alias.aliasFor(id), Alias.aliasFor(id))
        }
    }

    @Test
    fun goldenAliasesAreStable() {
        // Regression guard, computed from the salt and the word lists with Python rather than from this
        // code: any change to the salt, the byte-to-word mapping, or the lists' content or order moves these.
        assertEquals("OptimallyArtfulFox", Alias.aliasFor("node123"))
        assertEquals("ThriftilyWoollyOriole", Alias.aliasFor("k3p9xq2a"))
        assertEquals("MindfullyZealousZircon", Alias.aliasFor("aaaaaaaa"))
        assertEquals("EquallyQuaintMeerkat", Alias.aliasFor("zzzzzzzz"))
        assertEquals(
            "OptimallyArtfulFox AdeptlyPleasantMaple DecidedlyMildDune ArdentlyWonderfulBeacon WittilyCleverAtoll " +
                "QuietlyGallantTurtle HugelyGentleOriole LucidlySereneGarnet UtterlyUpbeatRidge BoldlyPolishedWren",
            Alias.phrase("node123", Alias.MAX_TOKENS),
        )
    }

    @Test
    fun aliasIsAlwaysThreeCapitalizedWordsNoDigits() {
        repeat(5_000) {
            val id = randomNodeId(Random(it.toLong()))
            val alias = Alias.aliasFor(id)
            assertTrue("'$alias' must be three PascalCase words", token.matches(alias))
        }
    }

    @Test
    fun aliasNeverEqualsRawNodeId() {
        repeat(1_000) {
            val id = randomNodeId(Random(it.toLong()))
            assertNotEquals(id, Alias.aliasFor(id))
        }
    }

    @Test
    fun distributionDoesNotCollapse() {
        // A sign-extension or indexing bug would collapse the range to a handful of aliases. Over many
        // distinct ids we expect almost-as-many distinct aliases.
        val rng = Random(99)
        val ids = (1..3_000).map { randomNodeId(rng) }.toSet()
        val aliases = ids.map { Alias.aliasFor(it) }.toSet()
        assertTrue(
            "expected wide spread, got ${aliases.size} aliases from ${ids.size} ids",
            aliases.size > ids.size * 2 / 3,
        )
    }

    @Test
    fun phraseIsAPrefixOfTheNextLongerPhrase() {
        // The label growth rule (PeerLabels) relies on this: a longer label only ever appends.
        val rng = Random(3)
        repeat(20) {
            val id = randomNodeId(rng)
            assertEquals(Alias.aliasFor(id), Alias.phrase(id, 1))
            for (k in 1 until Alias.MAX_TOKENS) {
                val shorter = Alias.phrase(id, k)
                val longer = Alias.phrase(id, k + 1)
                assertTrue("'$longer' must be $k + 1 tokens", phrase.matches(longer))
                assertTrue("'$longer' must extend '$shorter'", longer.startsWith("$shorter "))
            }
            assertEquals(Alias.tokens(id).take(4), Alias.tokens(id, 4))
        }
    }

    @Test
    fun tokensAreByteExact() {
        // The phrase is a word encoding of SHA-256("knit-alias-v2:" + id): byte 3t picks the adverb,
        // 3t + 1 the adjective, 3t + 2 the noun. This is the whole portable spec.
        val id = "node123"
        val digest = MessageDigest.getInstance("SHA-256").digest("knit-alias-v2:$id".encodeToByteArray())
        val tokens = Alias.tokens(id)
        assertEquals(Alias.MAX_TOKENS, tokens.size)
        tokens.forEachIndexed { t, word ->
            val expected =
                ALIAS_ADVERBS[digest[3 * t].toInt() and 0xFF] +
                    ALIAS_ADJECTIVES[digest[3 * t + 1].toInt() and 0xFF] +
                    ALIAS_NOUNS[digest[3 * t + 2].toInt() and 0xFF]
            assertEquals(expected, word)
        }
    }

    @Test
    fun tokensRejectOutOfRangeCounts() {
        for (count in listOf(0, -1, Alias.MAX_TOKENS + 1)) {
            assertThrows(IllegalArgumentException::class.java) { Alias.tokens("node123", count) }
        }
    }

    @Test
    fun wordListsAreExactly256SortedUniqueAsciiAndDisjoint() {
        val shape = Regex("^[A-Z][a-z]{2,9}$")
        val lists = listOf("adverbs" to ALIAS_ADVERBS, "adjectives" to ALIAS_ADJECTIVES, "nouns" to ALIAS_NOUNS)
        for ((name, list) in lists) {
            assertEquals("$name must be exactly one byte wide", 256, list.size)
            assertEquals("$name must not repeat a word", 256, list.toSet().size)
            assertEquals("$name must be sorted", list.sorted(), list)
            list.forEach { assertTrue("'$it' in $name must be 3-10 ASCII letters, capitalized", shape.matches(it)) }
        }
        assertTrue(ALIAS_ADVERBS.intersect(ALIAS_ADJECTIVES.toSet()).isEmpty())
        assertTrue(ALIAS_ADVERBS.intersect(ALIAS_NOUNS.toSet()).isEmpty())
        assertTrue(ALIAS_ADJECTIVES.intersect(ALIAS_NOUNS.toSet()).isEmpty())
    }

    @Test
    fun wordListFingerprintIsFrozen() {
        // The vocabulary is a wire-visible spec: every device, and the iOS port, maps the same bytes to the
        // same words. Reordering, replacing or removing an entry re-aliases every peer everywhere, so it
        // takes its own ADR — and a new fingerprint here.
        val text = (ALIAS_ADVERBS + ALIAS_ADJECTIVES + ALIAS_NOUNS).joinToString("\n") + "\n"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        assertEquals(
            "3d36fd522087180cef0ccaab6b6f3cc6f28aaf8a72816c44bcc9b778240164ed",
            digest.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun noAliasWordOrAdjacentPairIsProfane() {
        // A byte-exact mapping cannot re-roll a bad token, so the lists themselves must be clean — each
        // word, and every adverb+adjective and adjective+noun concatenation, against the shipped list.
        val terms = profanityTerms()
        assertTrue("the profanity list must load", terms.size > 100)
        (ALIAS_ADVERBS + ALIAS_ADJECTIVES + ALIAS_NOUNS).forEach {
            assertTrue("'$it' is on the profanity list", it.lowercase() !in terms)
        }
        for (a in ALIAS_ADVERBS) for (b in ALIAS_ADJECTIVES) assertTrue("$a$b", (a + b).lowercase() !in terms)
        for (a in ALIAS_ADJECTIVES) for (b in ALIAS_NOUNS) assertTrue("$a$b", (a + b).lowercase() !in terms)
    }

    @Test
    fun unfortunateTokensAreAbsent() {
        // Exact "AdjectiveNoun" pairs review has flagged as reading badly despite clean lists. There is no
        // re-roll any more: a hit here is fixed by changing a list (with a new fingerprint and ADR).
        val blocked = setOf("FatCow", "DirtyPig", "HardyWood")
        for (a in ALIAS_ADJECTIVES) for (b in ALIAS_NOUNS) assertTrue("$a$b reads badly", a + b !in blocked)
    }

    @Test
    fun displayNameForUsesAliasOnlyWhenNameBlank() {
        val id = "node123"
        val alias = Alias.aliasFor(id)
        assertEquals(alias, displayNameFor(null, id))
        assertEquals(alias, displayNameFor("", id))
        assertEquals(alias, displayNameFor("   ", id))
        assertEquals("Alice", displayNameFor("Alice", id))
    }

    /** The shipped `profanity_en.txt`, parsed as `WordList.load` does (see `WordListTest`). */
    private fun profanityTerms(): Set<String> {
        // Gradle runs unit tests with the module dir as the working dir.
        val file =
            listOf(
                "src/main/assets/moderation/profanity_en.txt",
                "app/src/main/assets/moderation/profanity_en.txt",
            ).map(::File).firstOrNull { it.exists() }
                ?: error("profanity_en.txt not found (cwd=${File(".").absolutePath})")
        return file
            .readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
