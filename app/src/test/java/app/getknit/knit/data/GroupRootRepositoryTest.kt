package app.getknit.knit.data

import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.mesh.spool.GroupRootState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GroupRootRepository] over the real Room SQL (the [RoomDbTest] pattern): row mapping fidelity and the
 * two stamps the KDoc says must never move once set — the mint-grace clock and the re-mint obligation
 * (`docs/SPOOL_PROTOCOL.md` §3.2) — plus the drain sweep, which retires a previous lineage in place and
 * leaves the live root alone. Until now the store was exercised only through the whole stack in
 * `mesh/lab/`, which the coverage job skips.
 */
class GroupRootRepositoryTest : RoomDbTest() {
    private val repo by lazy { GroupRootRepository(db.groupRootDao()) }

    private fun minted(
        groupId: String = GROUP,
        version: Int = 1,
        prevExpiresAt: Long = 0L,
        remintDueAt: Long = 0L,
    ) = GroupRootState(
        groupId = groupId,
        root = ByteArray(32) { version.toByte() },
        version = version,
        minter = "minter-$version",
        prevRoot = if (version > 1) ByteArray(32) { (version - 1).toByte() } else null,
        prevVersion = if (version > 1) version - 1 else 0,
        prevExpiresAt = prevExpiresAt,
        firstEligibleAt = T0,
        remintDueAt = remintDueAt,
    )

    @Test
    fun `a root round-trips every column and upsert replaces in place`() =
        runTest {
            repo.upsert(minted(version = 2, prevExpiresAt = T0 + 10, remintDueAt = T0 + 5))

            val state = checkNotNull(repo.find(GROUP))
            assertArrayEquals(ByteArray(32) { 2 }, state.root)
            assertEquals(2, state.version)
            assertEquals("minter-2", state.minter)
            assertArrayEquals(ByteArray(32) { 1 }, state.prevRoot)
            assertEquals(1, state.prevVersion)
            assertEquals(T0 + 10, state.prevExpiresAt)
            assertEquals(T0, state.firstEligibleAt)
            assertEquals(T0 + 5, state.remintDueAt)

            // A rotation writes the next lineage over the same row: one row per group, the newest wins.
            repo.upsert(minted(version = 3))
            assertEquals(3, checkNotNull(repo.find(GROUP)).version)
            assertEquals(listOf(GROUP), repo.all().map { it.groupId })
        }

    @Test
    fun `find is null for a group never seen and all lists every group`() =
        runTest {
            assertNull(repo.find("nobody"))
            repo.upsert(minted(groupId = "g1"))
            repo.upsert(minted(groupId = "g2"))
            assertEquals(setOf("g1", "g2"), repo.all().map { it.groupId }.toSet())
        }

    @Test
    fun `markEligible creates a root-less row and never moves a stamp already set`() =
        runTest {
            // The grace clock has to survive restarts, so the first stamp is a row of its own — no root yet.
            repo.markEligible(GROUP, T0)
            val first = checkNotNull(repo.find(GROUP))
            assertNull(first.root)
            assertEquals(T0, first.firstEligibleAt)

            // A second pass — every reconcile is one — would restart the grace, and the device would never mint.
            repo.markEligible(GROUP, T0 + 60_000)
            assertEquals(T0, checkNotNull(repo.find(GROUP)).firstEligibleAt)

            // Nor does adopting a root in between reset it: the stamp rides on the row the root lands in.
            repo.upsert(GroupRootState(GROUP, root = ByteArray(32) { 1 }, version = 1, minter = "m", firstEligibleAt = T0))
            repo.markEligible(GROUP, T0 + 120_000)
            assertEquals(T0, checkNotNull(repo.find(GROUP)).firstEligibleAt)
        }

    @Test
    fun `markEligible stamps a row that holds a root but no clock yet`() =
        runTest {
            // A root adopted from gossip before this device was ever eligible: the row exists with a zero
            // clock, and the first eligibility must be recorded on it, not skipped because the row is there.
            repo.upsert(GroupRootState(GROUP, root = ByteArray(32) { 7 }, version = 1, minter = "m"))
            repo.markEligible(GROUP, T0)
            val state = checkNotNull(repo.find(GROUP))
            assertEquals(T0, state.firstEligibleAt)
            assertArrayEquals("the root survived the stamp", ByteArray(32) { 7 }, state.root)
        }

    @Test
    fun `markRemintDue needs a root and never moves once recorded`() =
        runTest {
            // No row, and a row with no root: nothing to rotate, so nothing is owed.
            repo.markRemintDue(GROUP, T0)
            assertNull(repo.find(GROUP))
            repo.markEligible(GROUP, T0)
            repo.markRemintDue(GROUP, T0 + 1)
            assertEquals(0L, checkNotNull(repo.find(GROUP)).remintDueAt)

            repo.upsert(minted())
            repo.markRemintDue(GROUP, T0 + 10)
            assertEquals(T0 + 10, checkNotNull(repo.find(GROUP)).remintDueAt)

            // A re-served `groupleave` is processed again; the deadline it set the first time stays put.
            repo.markRemintDue(GROUP, T0 + 500_000)
            assertEquals(T0 + 10, checkNotNull(repo.find(GROUP)).remintDueAt)
        }

    @Test
    fun `the answering mint clears the obligation through upsert`() =
        runTest {
            repo.upsert(minted())
            repo.markRemintDue(GROUP, T0 + 10)
            repo.upsert(minted(version = 2, prevExpiresAt = T0 + 1_000, remintDueAt = 0L))
            assertEquals(0L, checkNotNull(repo.find(GROUP)).remintDueAt)
        }

    @Test
    fun `sweep retires a drained previous lineage in place and leaves everything else`() =
        runTest {
            repo.upsert(minted(groupId = "drained", version = 2, prevExpiresAt = T0 + 100))
            repo.upsert(minted(groupId = "draining", version = 2, prevExpiresAt = T0 + 100_000))
            repo.upsert(minted(groupId = "fresh", version = 1)) // no previous lineage at all
            repo.markRemintDue("drained", T0 + 50)

            repo.sweep(T0 + 100) // the boundary is inclusive: expired *at* now is drained

            val drained = checkNotNull(repo.find("drained"))
            assertNull(drained.prevRoot)
            assertEquals(0, drained.prevVersion)
            assertEquals(0L, drained.prevExpiresAt)
            assertArrayEquals("the live root is untouched", ByteArray(32) { 2 }, drained.root)
            assertEquals(2, drained.version)
            assertEquals("and so is every other column", T0 + 50, drained.remintDueAt)

            val draining = checkNotNull(repo.find("draining"))
            assertArrayEquals(ByteArray(32) { 1 }, draining.prevRoot)
            assertEquals(T0 + 100_000, draining.prevExpiresAt)

            val fresh = checkNotNull(repo.find("fresh"))
            assertNull(fresh.prevRoot)
            assertEquals(3, repo.all().size)
        }

    @Test
    fun `purge drops the group's row only`() =
        runTest {
            repo.upsert(minted(groupId = "g1"))
            repo.upsert(minted(groupId = "g2"))
            repo.purge("g1")
            assertNull(repo.find("g1"))
            assertNotNull(repo.find("g2"))
        }

    private companion object {
        const val GROUP = "group-1"
        const val T0 = 1_700_000_000_000L
    }
}
