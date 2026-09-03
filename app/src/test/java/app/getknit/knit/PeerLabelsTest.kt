package app.getknit.knit

import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.identity.displayNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PeerLabelsTest {
    private fun index(
        vararg peers: Pair<String, String>,
        self: Pair<String, String>? = null,
    ) = PeerLabels.index(peers.toList(), self)

    @Test
    fun aUniqueNameIsUndiscriminatedAndByteIdenticalToDisplayNameFor() {
        val idx = index("a" to "Alice", "b" to "Bob", "c" to "")
        for (id in listOf("a", "b", "c")) {
            val label = idx.labelFor(id)
            assertNull(label.discriminator)
            assertEquals(displayNameFor(idx.storedNameOf(id), id), label.text)
            assertEquals(Alias.aliasFor(id), label.alias)
        }
        // A blank name renders as the alias, exactly as before.
        assertEquals(Alias.aliasFor("c"), idx.labelFor("c").text)
    }

    @Test
    fun twoPeersRenderingToTheSameKeyBothGetTheirAlias() {
        val idx = index("a" to "Alice", "b" to "alice ", "c" to "Carol")
        val a = idx.labelFor("a")
        val b = idx.labelFor("b")
        assertEquals(Alias.aliasFor("a"), a.discriminator)
        assertEquals(Alias.aliasFor("b"), b.discriminator)
        assertEquals("Alice (${Alias.aliasFor("a")})", a.text)
        assertEquals("alice  (${Alias.aliasFor("b")})", b.text) // the stored name is rendered verbatim
        assertNotEquals(a.text, b.text)
        assertNull(idx.labelFor("c").discriminator)
    }

    @Test
    fun aThreeWayCollisionDiscriminatesEveryMember() {
        val idx = index("a" to "Sam", "b" to "Sam", "c" to "SAM")
        val texts = listOf("a", "b", "c").map { idx.labelFor(it).text }
        assertEquals(3, texts.toSet().size)
        listOf("a", "b", "c").forEach { assertEquals(Alias.aliasFor(it), idx.labelFor(it).discriminator) }
    }

    @Test
    fun selfIsPartOfTheUniverse() {
        // A peer who adopts our own name is discriminated (and so are we, where we are listed by name).
        val idx = index("a" to "Alice", self = "me" to "Alice")
        assertEquals(Alias.aliasFor("a"), idx.labelFor("a").discriminator)
        assertEquals(Alias.aliasFor("me"), idx.labelFor("me").discriminator)
    }

    @Test
    fun aSeededSelfRowDoesNotCollideWithSelf() {
        // The demo seeder upserts a peer row for ourselves; keyed by node id it is one identity, not two.
        val idx = index("me" to "Alice", "b" to "Bob", self = "me" to "Alice")
        assertNull(idx.labelFor("me").discriminator)
    }

    @Test
    fun anUnknownSenderIsDiscriminatedWhenItCollidesWithAKnownName() {
        val idx = index("a" to "Alice")
        val stranger = idx.labelFor("zz", "Alice")
        assertEquals(Alias.aliasFor("zz"), stranger.discriminator)
        assertNull(idx.labelFor("zz", "Zed").discriminator)
        assertNull(idx.labelFor("zz", null).discriminator)
        // The known peer is not retroactively discriminated by a lookup — the index is a snapshot.
        assertNull(idx.labelFor("a").discriminator)
    }

    @Test
    fun aBlankNamedPeerGrowsAContinuationWhenSomeoneChoosesItsAlias() {
        val impostor = Alias.aliasFor("a")
        val idx = index("a" to "", "b" to impostor)
        val a = idx.labelFor("a")
        // The rendered name already is the first token, so the discriminator is the token after it.
        assertEquals(Alias.tokens("a", 2)[1], a.discriminator)
        assertEquals(Alias.phrase("a", 2), a.alias)
        assertEquals("$impostor (${Alias.tokens("a", 2)[1]})", a.text)
        assertEquals(Alias.aliasFor("b"), idx.labelFor("b").discriminator)
        assertNotEquals(a.text, idx.labelFor("b").text)
    }

    @Test
    fun aGroundAliasMatchMakesBothLabelsGrowToTwoTokens() {
        // 24 bits is what a ground keypair buys; the labels answer with 48, and the growth is the tell.
        assertEquals(GROUND_X, NodeId.derive("alias-scan-58901"))
        assertEquals(GROUND_Y, NodeId.derive("alias-scan-110752"))
        assertEquals(Alias.aliasFor(GROUND_X), Alias.aliasFor(GROUND_Y))
        assertNotEquals(Alias.tokens(GROUND_X, 2)[1], Alias.tokens(GROUND_Y, 2)[1])
        val idx = index(GROUND_X to "Alice", GROUND_Y to "Alice")
        val lx = idx.labelFor(GROUND_X)
        val ly = idx.labelFor(GROUND_Y)
        assertEquals(Alias.phrase(GROUND_X, 2), lx.discriminator)
        assertEquals(Alias.phrase(GROUND_Y, 2), ly.discriminator)
        assertEquals(lx.discriminator, lx.alias) // what the person would quote
        assertNotEquals(lx.text, ly.text)
    }

    @Test
    fun aNameChosenToReadLikeAnotherPeersLabelIsStillDistinct() {
        val idx = index("a" to "Alice", "b" to "Alice", "c" to "Alice (${Alias.aliasFor("a")})")
        val texts = listOf("a", "b", "c").map { idx.labelFor(it).text }
        assertEquals(3, texts.toSet().size)
        assertEquals(Alias.phrase("a", 2), idx.labelFor("a").discriminator) // grew past the chosen name
        assertEquals(Alias.aliasFor("b"), idx.labelFor("b").discriminator) // untouched
        assertEquals(Alias.aliasFor("c"), idx.labelFor("c").discriminator) // caught by the text pass alone
    }

    @Test
    fun anOutsideIdGrowsUntilItReadsApartFromTheUniverse() {
        val idx = index(GROUND_X to "Alice", "b" to "Alice")
        assertEquals(Alias.aliasFor(GROUND_X), idx.labelFor(GROUND_X).discriminator)
        // A sender not yet in the universe whose alias matches a member's grows one token further.
        val outsider = idx.labelFor(GROUND_Y, "Alice")
        assertEquals(Alias.phrase(GROUND_Y, 2), outsider.discriminator)
        assertNotEquals(idx.labelFor(GROUND_X).text, outsider.text)
        // The member is not retroactively grown by a lookup — the index is a snapshot.
        assertEquals(Alias.aliasFor(GROUND_X), idx.labelFor(GROUND_X).discriminator)
    }

    @Test
    fun aMemberAskedUnderADifferentNameResolvesAgainstTheSnapshot() {
        // The contact-card preview asks for a known id under the card's name (ContactImporter).
        val idx = index("a" to "Alice", "b" to "Bob")
        assertEquals(Alias.aliasFor("a"), idx.labelFor("a", "Bob").discriminator)
        assertNull(idx.labelFor("a", "Carol").discriminator)
        assertNull(idx.labelFor("a").discriminator)
    }

    @Test
    fun twoBlankNamedPeersWithOneAliasReadApartByTheirContinuations() {
        val idx = index(GROUND_X to "", GROUND_Y to "")
        val lx = idx.labelFor(GROUND_X)
        assertEquals(Alias.aliasFor(GROUND_X), lx.name)
        assertEquals(Alias.tokens(GROUND_X, 2)[1], lx.discriminator)
        assertEquals(Alias.phrase(GROUND_X, 2), lx.alias)
        assertNotEquals(lx.text, idx.labelFor(GROUND_Y).text)
    }

    @Test
    fun everyLabelInAUniverseIsDistinct() {
        val rng = Random(7)
        val pool = listOf("Alice", "alice", "Bob", "Sam", "SAM", "Dani", "Theo", "Priya", "Jonas W.", "Lena F.", "")
        // 2,000 peers: the sweep cap, so the largest universe an index is ever built over.
        val peers = (0 until 2_000).map { NodeId.derive("peer-$it") to pool[rng.nextInt(pool.size)] }
        val idx = PeerLabels.index(peers, NodeId.derive("me") to "Alice")
        val texts = (peers.map { it.first } + NodeId.derive("me")).map { idx.labelFor(it).text }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun theEmptyIndexDiscriminatesNothing() {
        assertNull(PeerLabelIndex.EMPTY.labelFor("a", "Alice").discriminator)
        assertEquals("Alice", PeerLabelIndex.EMPTY.labelFor("a", "Alice").text)
    }

    private fun PeerLabelIndex.storedNameOf(id: String): String? = labelFor(id).let { if (it.name == Alias.aliasFor(id)) null else it.name }

    private companion object {
        /**
         * Two real node ids whose alias digests (`SHA-256("knit-alias-v2:" + id)`) share their first four
         * bytes, found by a birthday scan over `NodeId.derive("alias-scan-$i")`: the same first token, a
         * different second one — what a keypair ground to match an alias looks like.
         */
        const val GROUND_X = "jiuqkhusaqt3u25svz7rbvvwje"
        const val GROUND_Y = "p3ve2zdqk6ecz5dfofpywoobxa"
    }
}
