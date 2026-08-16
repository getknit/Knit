package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope-table derivation: who gets a scope, from which root, and for how long (spec §3.1). */
class ScopeRegistryTest {
    private val me = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"

    private val groupId = "g-00112233445566778899aabb"

    private fun registry(
        roots: List<ScopeRoots> = emptyList(),
        accepted: Set<String> = setOf(bob, carol),
        groups: List<GroupScopeRoots> = emptyList(),
    ) = ScopeRegistry(
        selfId = { me },
        roots = { roots },
        isAccepted = { it in accepted },
        groupRoots = { groups },
    )

    private fun root(seed: Byte) = ByteArray(32) { seed }

    @Test
    fun `derives one scope per accepted peer, matching the spec's DM derivation`() =
        runTest {
            val scopes = registry(listOf(ScopeRoots(bob, root(1)))).scopes(now = 0L)
            assertEquals(1, scopes.size)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), scopes[0].id)
            assertArrayEquals(ScopeCrypto.dmSealKeys(root(1), me, bob).sealKey, scopes[0].keys.sealKey)
            assertEquals(bob, scopes[0].peerId)
            assertFalse(scopes[0].retiring)
        }

    @Test
    fun `both members derive the same scope id from their shared pairwise root`() =
        runTest {
            val mine = registry(listOf(ScopeRoots(bob, root(1)))).scopes(0L).single()
            val theirs =
                ScopeRegistry(selfId = { bob }, roots = { listOf(ScopeRoots(me, root(1))) }, isAccepted = { true })
                    .scopes(0L)
                    .single()
            assertEquals(mine.idHex, theirs.idHex)
        }

    @Test
    fun `a peer that is still a message request gets no scope`() =
        runTest {
            val scopes = registry(listOf(ScopeRoots(bob, root(1)), ScopeRoots(carol, root(2))), accepted = setOf(carol)).scopes(0L)
            assertEquals(listOf(carol), scopes.map { it.peerId })
        }

    @Test
    fun `a replaced session keeps the retiring scope subscribed until its drain window closes`() =
        runTest {
            val entry = ScopeRoots(bob, root(3), prevPairwiseRoot = root(1), prevRootExpiresAt = 5_000L)
            val duringDrain = registry(listOf(entry)).scopes(now = 4_999L)
            assertEquals(2, duringDrain.size)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), duringDrain[0].id)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), duringDrain[1].id)
            assertTrue("the old scope is drained, never refilled", duringDrain[1].retiring)

            val afterDrain = registry(listOf(entry)).scopes(now = 5_000L)
            assertEquals(1, afterDrain.size)
            assertFalse(afterDrain[0].retiring)
        }

    @Test
    fun `bounds default to the spec's constants so a stock spool clamps them to themselves`() =
        runTest {
            val bounds = registry(listOf(ScopeRoots(bob, root(1)))).scopes(0L).single().bounds
            assertEquals(400, bounds.maxFrames)
            assertEquals(48 * 60 * 60_000L, bounds.ttlMs)
            assertEquals(64 * 1024, bounds.maxBlob)
        }

    @Test
    fun `a group with a root gets a scope keyed by root and version, carrying its founding roster`() =
        runTest {
            val roster = setOf(me, bob, carol)
            val scope =
                registry(groups = listOf(GroupScopeRoots(groupId, roster, root(7), rootVersion = 1)))
                    .scopes(0L)
                    .single()
            assertArrayEquals(ScopeCrypto.groupScopeId(root(7), groupId, 1), scope.id)
            assertArrayEquals(ScopeCrypto.groupSealKeys(root(7), groupId, 1).sealKey, scope.keys.sealKey)
            assertEquals(groupId, scope.groupId)
            assertEquals(roster, scope.roster)
            // The DM discriminator stays null, which is what ScopeFrames dispatches the frame-set rule on.
            assertEquals(null, scope.peerId)
            assertEquals(groupId, scope.label)
        }

    @Test
    fun `a group with no root gets no scope at all`() =
        runTest {
            assertTrue(registry(groups = emptyList()).scopes(0L).isEmpty())
        }

    @Test
    fun `a departure re-mint rotates the id and drains the old lineage`() =
        runTest {
            val entry =
                GroupScopeRoots(
                    groupId = groupId,
                    roster = setOf(me, bob),
                    root = root(9),
                    rootVersion = 2,
                    prevRoot = root(7),
                    prevRootVersion = 1,
                    prevRootExpiresAt = 5_000L,
                )
            val duringDrain = registry(groups = listOf(entry)).scopes(now = 4_999L)
            assertEquals(2, duringDrain.size)
            assertArrayEquals(ScopeCrypto.groupScopeId(root(9), groupId, 2), duringDrain[0].id)
            assertArrayEquals(ScopeCrypto.groupScopeId(root(7), groupId, 1), duringDrain[1].id)
            // Unlinkable to a spool: the rotated scope is a fresh id, not a re-keying of the old one.
            assertTrue(duringDrain[0].idHex != duringDrain[1].idHex)
            assertTrue("the old scope is drained, never refilled", duringDrain[1].retiring)

            val afterDrain = registry(groups = listOf(entry)).scopes(now = 5_000L)
            assertEquals(1, afterDrain.size)
            assertFalse(afterDrain[0].retiring)
        }

    @Test
    fun `every member derives the same group scope id from the shared root`() =
        runTest {
            val roster = setOf(me, bob)
            val mine = registry(groups = listOf(GroupScopeRoots(groupId, roster, root(7), 1))).scopes(0L).single()
            val theirs =
                ScopeRegistry(
                    selfId = { bob },
                    roots = { emptyList() },
                    isAccepted = { true },
                    groupRoots = { listOf(GroupScopeRoots(groupId, roster, root(7), 1)) },
                ).scopes(0L).single()
            assertEquals(mine.idHex, theirs.idHex)
        }

    @Test
    fun `DM and group scopes coexist in one table`() =
        runTest {
            val scopes =
                registry(
                    roots = listOf(ScopeRoots(bob, root(1))),
                    groups = listOf(GroupScopeRoots(groupId, setOf(me, bob), root(7), 1)),
                ).scopes(0L)
            assertEquals(listOf(bob, groupId), scopes.map { it.label })
        }
}
