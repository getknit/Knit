package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.protocol.GroupRootPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared group root's convergence rules (`docs/SPOOL_PROTOCOL.md` §3.2): who mints and when, what
 * may be adopted, and that competing v1 lineages actually collapse.
 */
class GroupRootPolicyTest {
    private val groupId = "g-00112233445566778899aabb"
    private val ann = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val cid = "cccccccccccccccccccccccccc"
    private val roster = setOf(ann, bob, cid)

    private fun root(seed: Byte) = ByteArray(GroupRootPolicy.ROOT_BYTES) { seed }

    private fun held(
        version: Int,
        minter: String,
        remintDueAt: Long = 0L,
        firstEligibleAt: Long = 0L,
    ) = GroupRootState(
        groupId = groupId,
        root = root(1),
        version = version,
        minter = minter,
        remintDueAt = remintDueAt,
        firstEligibleAt = firstEligibleAt,
    )

    private fun payload(
        version: Int,
        minter: String,
        rootBytes: ByteArray = root(2),
    ) = GroupRootPayload(root = rootBytes, version = version, minter = minter)

    // --- ordering ---

    @Test
    fun `anything beats holding no root`() {
        assertTrue(GroupRootPolicy.isNewer(1, ann, null))
        assertTrue(GroupRootPolicy.isNewer(1, ann, GroupRootState(groupId, firstEligibleAt = 5L)))
    }

    @Test
    fun `version dominates, and the minter breaks a tie lexicographically`() {
        assertTrue(GroupRootPolicy.isNewer(2, ann, held(1, cid)))
        assertFalse(GroupRootPolicy.isNewer(1, cid, held(2, ann)))
        assertTrue(GroupRootPolicy.isNewer(1, cid, held(1, bob)))
        assertFalse(GroupRootPolicy.isNewer(1, ann, held(1, bob)))
        // Idempotent: the same pair re-gossiped is not newer, so a re-served ctl DM changes nothing.
        assertFalse(GroupRootPolicy.isNewer(1, bob, held(1, bob)))
    }

    // --- who mints ---

    @Test
    fun `the creator mints when still a member, else the smallest remaining node id`() {
        assertEquals(cid, GroupRootPolicy.preferredMinter(createdBy = cid, members = roster))
        assertEquals(ann, GroupRootPolicy.preferredMinter(createdBy = "departed", members = roster))
        assertNull(GroupRootPolicy.preferredMinter(createdBy = ann, members = emptySet()))
    }

    @Test
    fun `the preferred minter mints version 1 immediately, with no state at all`() {
        assertEquals(1, GroupRootPolicy.mintDue(state = null, selfId = ann, preferredMinter = ann, now = 0L))
    }

    @Test
    fun `a non-preferred member waits out the grace before minting version 1`() {
        val stamped = GroupRootState(groupId, firstEligibleAt = 1_000L)
        assertNull(
            "before the grace elapses, a gossiped root may still arrive",
            GroupRootPolicy.mintDue(stamped, selfId = bob, preferredMinter = ann, now = 1_000L + GroupRootPolicy.MINT_GRACE_MS - 1),
        )
        assertEquals(
            1,
            GroupRootPolicy.mintDue(stamped, selfId = bob, preferredMinter = ann, now = 1_000L + GroupRootPolicy.MINT_GRACE_MS),
        )
    }

    @Test
    fun `an unstamped non-preferred member mints nothing — the grace clock has to start first`() {
        // The caller stamps eligibility before deciding; without that persisted stamp there is no clock,
        // and defaulting to "mint now" would make every restart a fresh lineage.
        assertNull(GroupRootPolicy.mintDue(state = null, selfId = bob, preferredMinter = ann, now = Long.MAX_VALUE / 2))
    }

    @Test
    fun `holding a root with nothing owed mints nothing`() {
        assertNull(GroupRootPolicy.mintDue(held(1, ann), selfId = ann, preferredMinter = ann, now = 9_999_999L))
    }

    @Test
    fun `a stamped departure re-mints the next version, immediately for the re-minter and after grace for others`() {
        val owed = held(version = 1, minter = cid, remintDueAt = 500L)
        assertEquals(2, GroupRootPolicy.mintDue(owed, selfId = ann, preferredMinter = ann, now = 500L))
        assertNull(GroupRootPolicy.mintDue(owed, selfId = bob, preferredMinter = ann, now = 500L))
        // ...and the grace is what keeps a re-minter who never comes back from freezing rotation forever.
        assertEquals(
            2,
            GroupRootPolicy.mintDue(owed, selfId = bob, preferredMinter = ann, now = 500L + GroupRootPolicy.MINT_GRACE_MS),
        )
    }

    @Test
    fun `minting stops at the version ceiling`() {
        val owed = held(version = GroupRootPolicy.MAX_ROOT_VERSION, minter = ann, remintDueAt = 1L)
        assertNull(GroupRootPolicy.mintDue(owed, selfId = ann, preferredMinter = ann, now = Long.MAX_VALUE / 2))
    }

    // --- adoption ---

    @Test
    fun `a strictly newer well-formed root from a roster minter is adopted`() {
        assertTrue(GroupRootPolicy.adoptable(payload(2, bob), roster, held(1, ann)))
        assertTrue(GroupRootPolicy.adoptable(payload(1, ann), roster, null))
    }

    @Test
    fun `a minter outside the founding roster is refused`() {
        // Without this, any member wins every tie forever by naming a lexicographically maximal minter id
        // that belongs to nobody.
        assertFalse(GroupRootPolicy.adoptable(payload(1, "zzzzzzzzzzzzzzzzzzzzzzzzzz"), roster, held(1, ann)))
    }

    @Test
    fun `a grief-mint far past the ceiling or the jump bound is refused`() {
        assertFalse(GroupRootPolicy.adoptable(payload(Int.MAX_VALUE, bob), roster, held(1, ann)))
        assertFalse(GroupRootPolicy.adoptable(payload(GroupRootPolicy.MAX_ROOT_VERSION + 1, bob), roster, null))
        assertFalse(
            GroupRootPolicy.adoptable(payload(GroupRootPolicy.MAX_ROOT_VERSION_JUMP + 1, bob), roster, null),
        )
        // A device that missed every departure of a full roster still fits inside the jump bound.
        assertTrue(GroupRootPolicy.adoptable(payload(GroupRootPolicy.MAX_ROOT_VERSION_JUMP, bob), roster, null))
    }

    @Test
    fun `a malformed root is refused`() {
        assertFalse(GroupRootPolicy.adoptable(payload(2, bob, rootBytes = ByteArray(16)), roster, held(1, ann)))
        assertFalse(GroupRootPolicy.adoptable(payload(0, bob), roster, held(1, ann)))
    }

    // --- rotation ---

    @Test
    fun `rotation retires the outgoing lineage into the drain window and discharges the obligation`() {
        val before = held(version = 1, minter = ann, remintDueAt = 500L, firstEligibleAt = 100L)
        val after = GroupRootPolicy.rotated(before, groupId, root(9), version = 2, minter = bob, now = 1_000L)
        assertArrayEquals(root(9), after.root)
        assertEquals(2, after.version)
        assertEquals(bob, after.minter)
        assertArrayEquals(root(1), after.prevRoot)
        assertEquals(1, after.prevVersion)
        assertEquals(1_000L + GroupRootPolicy.DRAIN_MS, after.prevExpiresAt)
        assertEquals("the obligation is discharged by the mint that answered it", 0L, after.remintDueAt)
        assertEquals("the grace clock is never restarted", 100L, after.firstEligibleAt)
    }

    @Test
    fun `a first mint has no lineage to drain`() {
        val after = GroupRootPolicy.rotated(GroupRootState(groupId, firstEligibleAt = 7L), groupId, root(9), 1, ann, 1_000L)
        assertNull(after.prevRoot)
        assertEquals(0L, after.prevExpiresAt)
    }

    // --- convergence ---

    @Test
    fun `three members that all mint version 1 collapse onto the largest minter`() {
        // The grace damps this, it does not forbid it: everyone's grace can expire at once. What matters
        // is that gossiping the newest each holds terminates on one lineage for everyone.
        val devices = mutableMapOf<String, GroupRootState>()
        roster.forEachIndexed { i, id ->
            devices[id] = GroupRootPolicy.rotated(null, groupId, root((i + 1).toByte()), 1, id, now = 1_000L)
        }
        // Gossip every held root at every device, repeatedly — order-independent by construction, so a
        // couple of full rounds is the whole convergence argument.
        repeat(2) {
            roster.forEach { receiver ->
                roster.forEach { sender ->
                    val theirs = devices.getValue(sender)
                    val gr = payload(theirs.version, theirs.minter, checkNotNull(theirs.root))
                    if (GroupRootPolicy.adoptable(gr, roster, devices[receiver])) {
                        devices[receiver] = GroupRootPolicy.rotated(devices[receiver], groupId, gr.root, gr.version, gr.minter, 2_000L)
                    }
                }
            }
        }
        val winner = devices.getValue(ann)
        assertEquals(cid, winner.minter)
        roster.forEach { id ->
            assertEquals(winner.minter, devices.getValue(id).minter)
            assertArrayEquals(winner.root, devices.getValue(id).root)
        }
        // ...and a further gossip round changes nothing, so the loop terminates rather than oscillating.
        assertFalse(GroupRootPolicy.adoptable(payload(winner.version, winner.minter, checkNotNull(winner.root)), roster, winner))
    }
}
