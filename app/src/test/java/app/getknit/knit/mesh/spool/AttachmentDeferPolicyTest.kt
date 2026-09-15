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
 * image.
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

    // The plane the recipient's receipt arrived on, or null for a frame nobody has acked. The production
    // lambda is `MessageDao.attachmentAckedOverRadio`, whose SQL is covered in `MessageDaoTest`.
    private var ackPlane: DeliveryPlane? = DeliveryPlane.Nearby

    private fun policy() =
        AttachmentDeferPolicy(
            reachable = { reachable },
            ackedOverRadio = { ackPlane?.shortRange == true },
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

    @Test
    fun `an attachment acked over a radio whose peer is on the presence plane waits for the radios`() =
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
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment acked over the spool is uploaded even with the peer in sight`() =
        runTest {
            // The evidence has to name a plane that could have carried the *bytes*. A receipt that came
            // back across a spool says the peer read us from anywhere at all, and deferring on it would
            // hold the upload back on the strength of the very plane the upload feeds.
            ackPlane = DeliveryPlane.Internet
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment acked over LoRa is uploaded even with the peer in sight`() =
        runTest {
            // A board carries a frame and never a blob, which is the same reason the reachable set is
            // narrowed to the short-range radios upstream.
            ackPlane = DeliveryPlane.LoRa
            assertFalse(policy().defer(dmScope(), ref()))
        }

    @Test
    fun `an attachment acked by a build too old to record a plane is uploaded`() =
        runTest {
            // `DeliveryPlane.Unknown` is what a row written before the column reads back as, and an
            // unknown plane is not evidence — the uncertain case resolves to push like every other.
            ackPlane = DeliveryPlane.Unknown
            assertFalse(policy().defer(dmScope(), ref()))
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
}
