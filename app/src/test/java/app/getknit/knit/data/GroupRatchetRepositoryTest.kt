package app.getknit.knit.data

import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupRatchetRepository] over the real Room SQL (the [RoomDbTest] pattern): row mapping fidelity,
 * the applyOpen delta semantics (mint-era-keyed rows, skipped-key lifecycle), the seed outbox, and
 * the retention sweep that enforces the group PFS window (docs/GROUP_FORWARD_SECRECY.md §10).
 */
class GroupRatchetRepositoryTest : RoomDbTest() {
    private val repo by lazy { GroupRatchetRepository(db.groupRatchetDao(), clock = { NOW }) }

    private fun sendChain(
        epoch: Int = 1,
        mintedAt: Long = NOW,
        count: Int = 0,
    ) = GroupRatchetEngine.SendChain(
        groupId = GROUP,
        epoch = epoch,
        seed = ByteArray(32) { 1 },
        chainKey = ByteArray(32) { (2 + epoch).toByte() },
        count = count,
        mintedAt = mintedAt,
        export = ByteArray(32) { 3 },
    )

    private fun recvChain(
        senderId: String = SENDER,
        epoch: Int = 1,
        mintedAt: Long = NOW,
        next: Int = 0,
        lastUsedAt: Long = NOW,
    ) = GroupRatchetEngine.RecvChain(
        groupId = GROUP,
        senderId = senderId,
        epoch = epoch,
        mintedAt = mintedAt,
        chainKey = ByteArray(32) { 4 },
        next = next,
        lastUsedAt = lastUsedAt,
    )

    @Test
    fun sendChainRoundTripsAndNewestWins() =
        runTest {
            repo.commitSend(sendChain(epoch = 1, count = 7))
            repo.commitSend(sendChain(epoch = 2, mintedAt = NOW + 5))

            val newest = checkNotNull(repo.sendChain(GROUP))
            assertEquals(2, newest.epoch)
            assertEquals(NOW + 5, newest.mintedAt)
            assertEquals(listOf(2, 1), repo.sendChains(GROUP).map { it.epoch })

            val older = repo.sendChains(GROUP).last()
            assertEquals(7, older.count)
            assertArrayEquals(ByteArray(32) { 1 }, older.seed)
        }

    @Test
    fun aSealAdvanceUpdatesTheSameEpochRow() =
        runTest {
            repo.commitSend(sendChain(epoch = 1, count = 0))
            repo.commitSend(sendChain(epoch = 1, count = 1))

            assertEquals(1, repo.sendChains(GROUP).size)
            assertEquals(1, checkNotNull(repo.sendChain(GROUP)).count)
        }

    @Test
    fun recvChainsComeBackNewestMintFirst() =
        runTest {
            repo.insertRecvChain(recvChain(mintedAt = NOW - 10))
            repo.insertRecvChain(recvChain(mintedAt = NOW))

            val chains = repo.recvChains(GROUP, SENDER, epoch = 1)
            assertEquals(listOf(NOW, NOW - 10), chains.map { it.mintedAt })
        }

    @Test
    fun applyOpenUpsertsTheChainAndCyclesSkippedKeys() =
        runTest {
            repo.insertRecvChain(recvChain())
            val skipped =
                listOf(
                    GroupRatchetEngine.SkippedKey(epoch = 1, mintedAt = NOW, idx = 0, msgKey = ByteArray(32) { 9 }, createdAt = NOW),
                    GroupRatchetEngine.SkippedKey(epoch = 1, mintedAt = NOW, idx = 1, msgKey = ByteArray(32) { 8 }, createdAt = NOW),
                )
            repo.applyOpen(
                GROUP,
                SENDER,
                GroupRatchetEngine.OpenDelta(recvChain = recvChain(next = 3), skippedInserts = skipped),
                headerSe = 1,
                headerN = 2,
            )

            assertEquals(3, repo.recvChains(GROUP, SENDER, 1).single().next)
            assertArrayEquals(ByteArray(32) { 9 }, repo.skippedKeys(GROUP, SENDER, 1, 0).single().msgKey)

            // Consuming idx 0 deletes every mint era's row at that position and touches nothing else.
            repo.applyOpen(
                GROUP,
                SENDER,
                GroupRatchetEngine.OpenDelta(recvChain = null, consumedSkippedIdx = 0),
                headerSe = 1,
                headerN = 0,
            )
            assertTrue(repo.skippedKeys(GROUP, SENDER, 1, 0).isEmpty())
            assertEquals(1, repo.skippedKeys(GROUP, SENDER, 1, 1).size)
        }

    @Test
    fun theOutboxTracksSendsAndAcksMonotonically() =
        runTest {
            repo.markKeySent(GROUP, MEMBER, epoch = 1, at = NOW)
            repo.markKeySent(GROUP, MEMBER, epoch = 2, at = NOW + 1)

            var row = checkNotNull(repo.keySend(GROUP, MEMBER))
            assertEquals(2, row.sentEpoch)
            assertEquals(0, row.ackedEpoch)

            repo.markKeyAcked(GROUP, MEMBER, epoch = 2, at = NOW + 2)
            row = checkNotNull(repo.keySend(GROUP, MEMBER))
            assertEquals(2, row.ackedEpoch)
            assertEquals(NOW + 2, row.ackedAt)

            // A stale (older-epoch) ack never rewinds; an ack with no row is ignored.
            repo.markKeyAcked(GROUP, MEMBER, epoch = 1, at = NOW + 3)
            assertEquals(2, checkNotNull(repo.keySend(GROUP, MEMBER)).ackedEpoch)
            repo.markKeyAcked(GROUP, "stranger0", epoch = 9, at = NOW)
            assertNull(repo.keySend(GROUP, "stranger0"))

            repo.deleteKeySend(GROUP, MEMBER)
            assertNull(repo.keySend(GROUP, MEMBER))
        }

    @Test
    fun purgeGroupDropsEveryTable() =
        runTest {
            repo.commitSend(sendChain())
            repo.insertRecvChain(recvChain())
            repo.applyOpen(
                GROUP,
                SENDER,
                GroupRatchetEngine.OpenDelta(
                    recvChain = null,
                    skippedInserts =
                        listOf(GroupRatchetEngine.SkippedKey(epoch = 1, mintedAt = NOW, idx = 0, msgKey = ByteArray(32), createdAt = NOW)),
                ),
                headerSe = 1,
                headerN = 0,
            )
            repo.markKeySent(GROUP, MEMBER, epoch = 1, at = NOW)

            repo.purgeGroup(GROUP)

            assertNull(repo.sendChain(GROUP))
            assertTrue(repo.recvChains(GROUP, SENDER, 1).isEmpty())
            assertTrue(repo.skippedKeys(GROUP, SENDER, 1, 0).isEmpty())
            assertNull(repo.keySend(GROUP, MEMBER))
        }

    @Test
    fun sweepEnforcesTheRetentionWindows() =
        runTest {
            val old = NOW - GroupRatchetRepository.SKIPPED_TTL_MS - 1
            repo.insertRecvChain(recvChain(epoch = 1, lastUsedAt = old))
            repo.insertRecvChain(recvChain(epoch = 2))
            repo.applyOpen(
                GROUP,
                SENDER,
                GroupRatchetEngine.OpenDelta(
                    recvChain = null,
                    skippedInserts =
                        listOf(
                            GroupRatchetEngine.SkippedKey(epoch = 1, mintedAt = NOW, idx = 0, msgKey = ByteArray(32), createdAt = old),
                            GroupRatchetEngine.SkippedKey(epoch = 2, mintedAt = NOW, idx = 0, msgKey = ByteArray(32), createdAt = NOW),
                        ),
                ),
                headerSe = 1,
                headerN = 9,
            )

            repo.sweep(NOW)

            assertTrue(repo.recvChains(GROUP, SENDER, 1).isEmpty())
            assertEquals(1, repo.recvChains(GROUP, SENDER, 2).size)
            assertTrue(repo.skippedKeys(GROUP, SENDER, 1, 0).isEmpty())
            assertEquals(1, repo.skippedKeys(GROUP, SENDER, 2, 0).size)
        }

    @Test
    fun sweepRetiresSupersededSendChainsButDrainsThePreviousOne() =
        runTest {
            repo.commitSend(sendChain(epoch = 1, mintedAt = NOW - 10))
            repo.commitSend(sendChain(epoch = 2, mintedAt = NOW - 5))
            repo.commitSend(sendChain(epoch = 3, mintedAt = NOW))

            // The newest mint is fresh: keep it and the draining previous epoch, drop the rest.
            repo.sweep(NOW)
            assertEquals(listOf(3, 2), repo.sendChains(GROUP).map { it.epoch })

            // Once the newest mint is older than the drain window, only it survives.
            repo.sweep(NOW + GroupRatchetRepository.PREV_SEED_RETAIN_MS)
            assertEquals(listOf(3), repo.sendChains(GROUP).map { it.epoch })
            assertNotNull(repo.sendChain(GROUP))
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val GROUP = "g-0123456789abcdef01234567"
        const val SENDER = "sender00"
        const val MEMBER = "member00"
    }
}
