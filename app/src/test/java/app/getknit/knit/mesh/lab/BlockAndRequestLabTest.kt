package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two local presentation decisions the mesh must never observe (ADR 009, ADR 010): a message request
 * that was never accepted is custodied and relayed like any DM, and a blocked sender's room post is still
 * acked — blocking stays invisible to the blocked party — while never surfacing on the blocker. Both ADRs
 * say the decision is "never folded into custody/relay"; the full oracle is what checks that.
 */
@RunWith(RobolectricTestRunner::class)
class BlockAndRequestLabTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /**
     * FINDING (2026-09-14, first run): the delivery half holds — Bob's room never shows the post and Alice
     * still gets Bob's tick — but the custody oracle fails: `InboundPipeline.canCarry` refuses a blocked
     * author, so Bob's live set lacks every frame Alice sends while Carol's holds them, and since the
     * digest exchange pushes what a peer lacks, Carol re-serves them to Bob on every exchange and Bob
     * refuses them every time — the "digests diverge, NAN churns forever" class, for as long as the block
     * stands. ADR 010 says blocking is "never folded into custody/relay"; `context/store-and-forward.md`
     * says a carrier stores only a non-blocked sender's frame. The two disagree and the code follows the
     * second. Ignored until that is decided; the router already relays a blocked sender's frames live, so
     * carrying them is the consistent choice.
     */
    @Ignore("finding: canCarry folds the block list into custody, so a blocker's digest never converges — awaiting a decision")
    @Test
    fun aBlockedSendersRoomPostIsStillAckedAndBlockingStaysInvisible() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            bob.block(alice)
            assertTrue(alice.sendRoom("from someone bob blocked"))
            val post = alice.ownMessageId(Conversations.NEARBY, "from someone bob blocked")
            lab.awaitReceipt(alice, post, bob)
            lab.awaitReceipt(alice, post, carol)
            assertNull("bob's room never shows the post", bob.roomPosts()[alice.nodeId])

            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { Conversations.NEARBY }
        }

    /** A message request Carol never answers is still carried by Bob and ticked by Carol: presentation only. */
    @Test
    fun aMessageRequestIsCustodiedAndRelayedLikeAnyDm() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            assertTrue(alice.sendDm(carol, "a request"))
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }

            carol.accept(alice.nodeId)
            assertTrue(carol.sendDm(alice, "accepted"))
            lab.assertConverged(listOf(alice, carol), atLeast = 2, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }
}
