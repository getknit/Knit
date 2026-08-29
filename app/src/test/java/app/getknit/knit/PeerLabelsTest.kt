package app.getknit.knit

import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.PeerLabelIndex
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.identity.displayNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aBlankNamedPeerFallsBackToTheShortIdWhenSomeoneChoosesItsAlias() {
        val impostor = Alias.aliasFor("a")
        val idx = index("a" to "", "b" to impostor)
        assertEquals(NodeId.shortForm("a"), idx.labelFor("a").discriminator)
        assertEquals(Alias.aliasFor("b"), idx.labelFor("b").discriminator)
        assertNotEquals(idx.labelFor("a").text, idx.labelFor("b").text)
    }

    @Test
    fun equalAliasesAppendTheShortIdSoLabelsStayDistinct() {
        val (x, y) = firstAliasCollision()
        val idx = index(x to "Alice", y to "Alice")
        val lx = idx.labelFor(x)
        val ly = idx.labelFor(y)
        assertEquals("${Alias.aliasFor(x)} ${NodeId.shortForm(x)}", lx.discriminator)
        assertEquals("${Alias.aliasFor(y)} ${NodeId.shortForm(y)}", ly.discriminator)
        assertNotEquals(lx.text, ly.text)
    }

    @Test
    fun aNameChosenToReadLikeAnotherPeersLabelIsStillDistinct() {
        val idx = index("a" to "Alice", "b" to "Alice", "c" to "Alice (${Alias.aliasFor("a")})")
        val texts = listOf("a", "b", "c").map { idx.labelFor(it).text }
        assertEquals(3, texts.toSet().size)
        assertTrue(idx.labelFor("c").text.endsWith("(${NodeId.shortForm("c")})"))
    }

    @Test
    fun everyLabelInAUniverseIsDistinct() {
        val rng = Random(7)
        val pool = listOf("Alice", "alice", "Bob", "Sam", "SAM", "Dani", "Theo", "Priya", "Jonas W.", "Lena F.", "")
        val peers = (0 until 500).map { NodeId.derive("peer-$it") to pool[rng.nextInt(pool.size)] }
        val idx = PeerLabels.index(peers, NodeId.derive("me") to "Alice")
        val texts = (peers.map { it.first } + NodeId.derive("me")).map { idx.labelFor(it).text }
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun theEmptyIndexDiscriminatesNothing() {
        assertNull(PeerLabelIndex.EMPTY.labelFor("a", "Alice").discriminator)
        assertEquals("Alice", PeerLabelIndex.EMPTY.labelFor("a", "Alice").text)
    }

    /** Two distinct ids sharing an alias — a ~15-bit space, so a scan finds a birthday pair quickly. */
    private fun firstAliasCollision(): Pair<String, String> {
        val seen = HashMap<String, String>()
        for (i in 0 until 100_000) {
            val id = NodeId.derive("alias-scan-$i")
            val prior = seen.put(Alias.aliasFor(id), id)
            if (prior != null && NodeId.shortForm(prior) != NodeId.shortForm(id)) return prior to id
        }
        error("no alias collision found")
    }

    private fun PeerLabelIndex.storedNameOf(id: String): String? = labelFor(id).let { if (it.name == Alias.aliasFor(id)) null else it.name }
}
