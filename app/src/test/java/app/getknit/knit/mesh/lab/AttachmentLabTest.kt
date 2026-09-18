package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Bytes that ride beside a frame: the `blobreq` / file pull (`BlobExchange`, `MeshBlobStore`), the sealed
 * attachment whose frame names only the ciphertext hash (ADR 035), the carrier that eager-pulls what it
 * custodies so an absent recipient can fetch it later, and screening on the receiver (knit-next#30). The
 * oracle's attachment check reads the bytes back on every node and compares the plaintext.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentLabTest {
    private lateinit var lab: MeshLab

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    @Test
    fun anImageDmArrivesWithItsBytesAndAScreenedVerdict() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val picture = Random(1).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "a picture", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "a picture")

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            assertTrue("bob screened what he received", bob.attachmentScreened(bob.dmWith(alice), id))
            assertEquals(
                "the frame names the ciphertext, never the picture",
                false,
                alice.attachmentHash(alice.dmWith(bob), id) ==
                    app.getknit.knit.mesh
                        .sha256Hex(picture),
            )
        }

    /**
     * Carol is away when Alice sends her a picture; Bob custodies the frame and pulls its bytes at once so
     * that, with Alice gone too, Carol can fetch both from him alone.
     */
    @Test
    fun anImageForSomeoneAwayIsCarriedWithItsBytesAndPulledFromTheCarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            val picture = Random(2).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for carol", to = carol))
            val id = alice.ownMessageId(alice.dmWith(carol), "for carol")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(carol), id))
            assertTrue("bob never pulled the bytes he carries the frame for", lab.tryAwait(1) { if (bob.blobs.exists(hash)) 1 else 0 })
            lab.unlink(alice, bob)

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(alice) }
            assertTrue(picture.contentEquals(carol.attachmentPlain(carol.dmWith(alice), id)))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /** The room is the one cleartext surface: every receiver pulls the picture and screens it itself. */
    @Test
    fun aRoomImageIsScreenedOnEveryReceiver() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            val picture = Random(3).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "room picture"))
            val id = alice.ownMessageId(Conversations.NEARBY, "room picture")

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} screened the room picture", n.attachmentScreened(Conversations.NEARBY, id))
                assertTrue(picture.contentEquals(n.attachmentPlain(Conversations.NEARBY, id)))
            }
        }

    @Test
    fun aGroupPhotoCrossesAsABlob() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            val photo = Random(4).nextBytes(4_096)
            alice.setGroupPhoto(groupId, photo)

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            val hash = checkNotNull(bob.group(groupId)?.photoHash)
            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} never pulled the group photo", lab.tryAwait(1) { if (n.blobs.exists(hash)) 1 else 0 })
                assertTrue(photo.contentEquals(n.blobs.bytes(hash)))
            }
        }

    /**
     * The 2026-09-15 field trial (ADR 2026-09.ptv8): a picture posted in the room reaches a phone that only
     * the board can hear, so its row arrives with a hash and no bytes, and the want it parks has nobody to
     * go to. The phone stays alone past `BlobExchange`'s 30-min fetch TTL — a real walk — and the sweep the
     * prune loop and the heartbeat run reclaims the want. When a neighbour finally links, the re-ask must
     * still name the picture: the database, not the in-memory memo, is what says it is missing. Before the
     * fix the row spun until the app was restarted; here Bob's re-link is the whole cure.
     */
    @Test
    fun aPictureHeardOverTheBoardWhileAloneIsStillFetchedAfterALongIsolation() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.awaitCustodyParity(alice, bob)
            lab.unlink(alice, bob)

            val picture = Random(5).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "from the trail"))
            val id = alice.ownMessageId(Conversations.NEARBY, "from the trail")
            assertTrue("bob never heard the post", lab.tryAwait(1) { bob.roomPosts()[alice.nodeId].orEmpty().size })
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, id))
            assertFalse("a board carries a frame, never its bytes", bob.attachmentHeld(Conversations.NEARBY, id))

            // Half an hour alone, then the sweep that reclaims a want nobody could be asked for.
            lab.clock.advance(WANT_TTL_LAPSED_MS)
            bob.sweepExpired()
            assertFalse(bob.attachmentHeld(Conversations.NEARBY, id))

            lab.link(alice, bob)
            assertTrue(
                "the picture was never fetched once a holder linked",
                lab.tryAwait(1) { if (bob.attachmentHeld(Conversations.NEARBY, id)) 1 else 0 },
            )
            assertTrue(picture.contentEquals(bob.attachmentPlain(Conversations.NEARBY, id)))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
        }

    private companion object {
        /** Past `BlobExchange.FETCH_TTL_MS` (30 min), well inside the 48 h a lab clock jump may span. */
        const val WANT_TTL_LAPSED_MS = 31 * 60_000L
    }
}
