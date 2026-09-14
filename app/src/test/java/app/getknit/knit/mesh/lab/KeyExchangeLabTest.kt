package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A frame whose sender's key is not here yet. Two recoveries exist and neither had a cross-node test: the
 * inbound one (`KeyExchange` — park the frame, ask the neighbors for the key with a `keyreq`, a holder serves
 * the profile verbatim, the park replays; "verified on devices" until now), and the outbound one (a DM
 * composed before the recipient's key is known is saved `pendingKey` and re-sealed the moment their profile
 * lands — `context/store-and-forward.md`, retransmit-on-key-arrival).
 */
@RunWith(RobolectricTestRunner::class)
class KeyExchangeLabTest {
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
     * Carol knows Bob. Alice joins on Bob's far side and posts; Bob relays her profile and her post toward
     * Carol, but only the post gets through. Carol cannot verify a stranger, so she parks the post and asks
     * Bob for the key; Bob serves Alice's profile; Carol pins it, replays the post, and the three custodies
     * agree — the served profile is a frame like any other.
     */
    @Test
    fun aFrameFromAStrangerIsParkedAndReplayedWhenTheKeyIsFetched() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.link(bob, carol)
            lab.awaitAcquainted(bob, carol)

            bob.transport.hold(carol.transport)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendRoom("from a stranger"))
            assertTrue(
                "bob never relayed alice's post",
                lab.await(1) { bob.transport.held(carol.transport).count { it.isChatFrom(alice.nodeId) } },
            )
            val released = bob.transport.release(carol.transport) { batch -> batch.filter { it.isChatFrom(alice.nodeId) } }
            assertEquals(1, released.size)

            assertTrue("carol never refused the stranger's frame", lab.await(1) { carol.drops(DropReason.NO_SENDER_KEY).toInt() })
            assertTrue(
                "carol never asked for the key",
                lab.await(1) {
                    carol.metrics
                        .snapshot()
                        .keyRequestsSent
                        .toInt()
                },
            )
            assertTrue(
                "bob never served it",
                lab.await(1) {
                    bob.metrics
                        .snapshot()
                        .keysServed
                        .toInt()
                },
            )
            assertTrue("carol never pinned alice", lab.await(1) { if (carol.knows(alice)) 1 else 0 })

            // FINDING (2026-09-14, first run): the served profile is a point-to-point `relay = false` frame,
            // so Carol delivers it without custodying it — and having seen its id, her router dedups the
            // custodial copy the next digest exchange serves for the whole SeenSet window (10 min). Her
            // custody is short exactly that one frame until the window lapses and a re-offer lands; the
            // relayed copies the hold ate are the digest exchange's to repair (a re-link does that part).
            // The full oracle therefore waits for the clock tier (TimeLabTest); here the delivery half and
            // the exact shape of the transient gap are pinned.
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            assertTrue(lab.await(1) { if (carol.roomPosts()[alice.nodeId]?.contains("from a stranger") == true) 1 else 0 })
            assertTrue(lab.await(1) { if (bob.custodyIds().minus(carol.custodyIds()).size <= 1) 1 else 0 })
            val short = bob.custodyIds() - carol.custodyIds()
            assertTrue(
                "carol is short at most the served profile, got $short",
                short.size <= 1 && short.all { it.startsWith("profile-${alice.nodeId}-") },
            )
            val snap = carol.metrics.snapshot()
            assertEquals("one frame parked, one replayed", 1L to 1L, snap.framesHeld to snap.framesReplayed)
            assertEquals("the missing key was recovered", 1L, snap.keysRecovered)
            assertEquals("Alice", carol.peer(alice)?.name)
        }

    /**
     * Alice writes to Carol before ever meeting her — no row, no key — and the DM parks as `pendingKey`.
     * When they meet, Carol's profile pins the key and the DM is sealed and flooded under its original id.
     */
    @Test
    fun aDmComposedBeforeTheKeyIsKnownFloodsWhenTheProfileLands() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            assertTrue(alice.sendDm(carol, "composed early"))
            val early = alice.ownMessageId(alice.dmWith(carol), "composed early")
            assertTrue(
                "the DM parked on pendingKey",
                alice.messages
                    .observeNewestMessages(alice.dmWith(carol), MeshLab.WINDOW)
                    .first()
                    .single {
                        it.id ==
                            early
                    }.pendingKey,
            )

            lab.link(alice, carol)
            lab.assertConverged(listOf(alice, carol), atLeast = 1) { it.dmWith(if (it === alice) carol else alice) }
            assertEquals(early, carol.decrypted(carol.dmWith(alice)).single().first)
        }

    private fun WireEnvelope.isChatFrom(nodeId: String): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.senderId == nodeId } == true
}
