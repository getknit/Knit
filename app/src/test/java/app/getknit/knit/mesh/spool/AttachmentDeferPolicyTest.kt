package app.getknit.knit.mesh.spool

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an attachment's bytes may wait for the radios instead of crossing a spool
 * (`docs/SPOOL_PROTOCOL.md` §9.5). The property every case here is really testing is the same one: the
 * gate defers only on positive evidence and re-opens by itself, so no uncertain case can strand an
 * image. The one softening is [AttachmentDeferPolicy.ACK_GRACE_MS], and it is bounded by the frame's
 * own age: for that long after a send there has been no time for an ack, so its absence is not yet
 * evidence of anything. Cases that are about the *plane* rule therefore age their frame past the grace
 * with [settled], or they would be testing the grace instead.
 */
class AttachmentDeferPolicyTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val groupId = "g-00112233445566778899aabb"
    private val aHash = "a".repeat(64)
    private val custodyTtlMs = 24 * 60 * 60_000L
    private val start = 1_700_000_000_000L

    private var clock = start
    private var reachable = setOf(bob)

    // The plane this attachment's message crossed between us and bob, or null when no row names it at
    // all — a carried frame or an avatar. The production lambda is `MessageDao.attachmentCarriedByRadio`,
    // which reads our own send's ack or their send's arrival; its SQL is covered in `MessageDaoTest`.
    private var ackPlane: DeliveryPlane? = DeliveryPlane.Nearby

    // Whether the message naming this attachment is one WE authored, which is the grace's subject and
    // nothing else: a photo bob sent us is not waiting on an ack of ours, so it never needs the grace.
    private var authored = true

    private fun policy() =
        AttachmentDeferPolicy(
            reachable = { reachable },
            carriedByRadio = { _, _ -> ackPlane?.shortRange == true },
            authoredHere = { authored },
            custodyTtlMs = custodyTtlMs,
            clock = { clock },
        )

    private fun dmScope() =
        Scope(
            id = ByteArray(ScopeCrypto.SCOPE_ID_BYTES) { 1 },
            keys = ScopeCrypto.dmSealKeys(ByteArray(32) { 9 }, alice, bob),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            peerId = bob,
        )

    private fun groupScope() =
        Scope(
            id = ByteArray(ScopeCrypto.SCOPE_ID_BYTES) { 2 },
            keys = ScopeCrypto.groupSealKeys(ByteArray(32) { 9 }, groupId, 1),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            groupId = groupId,
            roster = setOf(alice, bob),
        )

    private fun ref(sentAt: Long = start) = ScopeAttachments.Ref(aHash = aHash, mime = "image/jpeg", sentAt = sentAt)

    /** A frame old enough that an ack would have come back by now, so only the ordinary rule applies. */
    private fun settled() = ref(sentAt = start - AttachmentDeferPolicy.ACK_GRACE_MS)

    @Test
    fun `an attachment a radio carried whose peer is on the presence plane waits for the radios`() =
        runTest {
            // Both halves satisfied: a short-range receipt proves a data path worked for this pair, and the
            // peer is still in sight. This is the only shape that defers.
            assertTrue(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `a peer that has gone quiet is uploaded to`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The sighting is what expires, not the ack: the same acked frame now pushes.
            reachable = emptySet()
            clock = start + AttachmentDeferPolicy.RADIO_WINDOW_MS + 1
            assertFalse(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `a peer still inside the window keeps deferring through ordinary radio silence`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // A BLE scan floored to ~2 min or a dozing NAN peer is silence, not departure.
            reachable = emptySet()
            clock = start + AttachmentDeferPolicy.RADIO_WINDOW_MS - 1
            assertTrue(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `an unacked attachment is uploaded even with the peer in sight`() =
        runTest {
            // Presence is the cue plane — a peer can be reachable with no data path at all, so an
            // un-ticked frame is exactly the case the Internet plane exists for.
            ackPlane = null
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked over the spool is uploaded even with the peer in sight`() =
        runTest {
            // The evidence has to name a plane that could have carried the *bytes*. A receipt that came
            // back across a spool says the peer read us from anywhere at all, and deferring on it would
            // hold the upload back on the strength of the very plane the upload feeds.
            ackPlane = DeliveryPlane.Internet
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked over LoRa is uploaded even with the peer in sight`() =
        runTest {
            // A board carries a frame and never a blob, which is the same reason the reachable set is
            // narrowed to the short-range radios upstream.
            ackPlane = DeliveryPlane.LoRa
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `an attachment acked by a build too old to record a plane is uploaded`() =
        runTest {
            // `DeliveryPlane.Unknown` is what a row written before the column reads back as, and an
            // unknown plane is not evidence — the uncertain case resolves to push like every other.
            ackPlane = DeliveryPlane.Unknown
            assertFalse(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `a fresh process defers nothing`() =
        runTest {
            // No sighting has been recorded yet for a peer that is not reachable right now, so a restart
            // errs toward uploading rather than toward a silent hold.
            reachable = emptySet()
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `a group scope never defers`() =
        runTest {
            // The sealed group tick flips on the FIRST member's receipt, so it can never mean "everyone
            // holds it" — deferring on it would strand whoever was not reached.
            reachable = setOf(alice, bob)
            assertFalse(policy().defer(groupScope(), ref()))
        }

    @Test
    fun `deferring stops before the frame leaves custody`() =
        runTest {
            val subject = policy()
            // Inside the last-call window the frame is about to stop driving a push at all, so the bytes
            // go now or never.
            val expiring = start - custodyTtlMs + AttachmentDeferPolicy.LAST_CALL_MS
            assertFalse(subject.defer(dmScope(), ref(sentAt = expiring)))
            // One millisecond earlier in the frame's life and there is still time to wait.
            assertTrue(subject.defer(dmScope(), ref(sentAt = expiring + 1)))
        }

    @Test
    fun `an attachment whose ack has not had time to come back waits`() =
        runTest {
            // The round that asks this is the one the send itself woke, so the recipient's receipt is
            // still a round trip away. A missing ack here says nothing about the radios (issue #46).
            ackPlane = null
            assertTrue(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment nobody acked in time is uploaded once the grace is over`() =
        runTest {
            ackPlane = null
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The grace expires on the frame's own age, with no new local activity and no second signal.
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertFalse(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `a radio ack carries the deferral on past the grace`() =
        runTest {
            val subject = policy()
            assertTrue(subject.defer(dmScope(), ref()))

            // The hand-over the gate depends on: the receipt lands inside the grace and the ordinary rule
            // takes over, so there is no round in which the bytes are pushable.
            clock = start + AttachmentDeferPolicy.ACK_GRACE_MS
            assertTrue(subject.defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment the peer sent us over a radio waits too`() =
        runTest {
            // The other reading of the same fact: these bytes already crossed a radio between this scope's
            // only two members, so a second copy at the relay buys nobody anything. No grace is involved —
            // the arriving row's plane is known the instant the row exists.
            authored = false
            assertTrue(policy().defer(dmScope(), settled()))
        }

    @Test
    fun `a carried attachment is uploaded inside the grace`() =
        runTest {
            // A carrier holds sealed bytes and no message row, and an avatar writes none either, so the
            // evidence query finds nothing between this pair — and neither may borrow the grace, which
            // belongs to a send of ours still waiting on its ack.
            ackPlane = null
            authored = false
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `the grace does not rescue a peer that was never sighted`() =
        runTest {
            // The grace softens the ack half only. Presence is still required, so a fresh process and a
            // peer we hold no sighting for both push exactly as before.
            ackPlane = null
            reachable = emptySet()
            assertFalse(policy().defer(dmScope(), ref()))
        }
}
