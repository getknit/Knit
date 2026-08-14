package app.getknit.knit.data

import app.getknit.knit.data.ratchet.RatchetLocalEpochEntity
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.ratchet.RatchetSkippedKeyEntity
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RatchetRepository] over the real Room SQL (the [RoomDbTest] pattern): row mapping fidelity, the
 * applyOpen delta semantics (upsert + skipped-key lifecycle + replacement purge), and the retention
 * sweep that enforces the PFS window.
 */
class RatchetRepositoryTest : RoomDbTest() {
    private val repo by lazy { RatchetRepository(db.ratchetDao(), clock = { NOW }) }

    private fun session(
        peerId: String = "peer0000",
        sendEpoch: Int = 1,
        highestPeAcked: Int = 0,
    ) = RatchetEngine.SessionState(
        peerId = peerId,
        confirmed = true,
        weAreInitiator = true,
        root = ByteArray(32) { 1 },
        establishedAt = NOW,
        sendEpoch = sendEpoch,
        sendEpochPub = ByteArray(32) { 2 },
        sendChainKey = ByteArray(32) { 3 },
        sendEpochStartedAt = NOW,
        highestPeAcked = highestPeAcked,
    )

    @Test
    fun sessionRoundTripsEveryField() =
        runTest {
            val state =
                session().copy(
                    prevRoot = ByteArray(32) { 9 },
                    prevRootWeAreInitiator = true,
                    prevRootExpiresAt = NOW + 1,
                    initEphPub = ByteArray(32) { 4 },
                    initPkid = 7,
                    peerInitEphPub = ByteArray(32) { 5 },
                    peerBasePub = ByteArray(32) { 6 },
                    peerBaseEpoch = 3,
                    sendCount = 42,
                    sendEpochBaseEpoch = 2,
                    sendEpochExport = ByteArray(32) { 7 },
                    highestPeAcked = 2,
                    lastResetSentAt = NOW - 5,
                )
            repo.upsertSession(state)
            val loaded = checkNotNull(repo.session(state.peerId))
            // Data-class equality is reference-based on the ByteArray fields, so compare field-by-field.
            assertArrayEquals(state.root, loaded.root)
            assertArrayEquals(state.prevRoot, loaded.prevRoot)
            assertArrayEquals(state.initEphPub, loaded.initEphPub)
            assertArrayEquals(state.peerInitEphPub, loaded.peerInitEphPub)
            assertArrayEquals(state.peerBasePub, loaded.peerBasePub)
            assertArrayEquals(state.sendEpochPub, loaded.sendEpochPub)
            assertArrayEquals(state.sendChainKey, loaded.sendChainKey)
            assertArrayEquals(state.sendEpochExport, loaded.sendEpochExport)
            assertEquals(state.confirmed, loaded.confirmed)
            assertEquals(state.weAreInitiator, loaded.weAreInitiator)
            assertEquals(state.prevRootWeAreInitiator, loaded.prevRootWeAreInitiator)
            assertEquals(state.prevRootExpiresAt, loaded.prevRootExpiresAt)
            assertEquals(state.establishedAt, loaded.establishedAt)
            assertEquals(state.initPkid, loaded.initPkid)
            assertEquals(state.peerBaseEpoch, loaded.peerBaseEpoch)
            assertEquals(state.sendEpoch, loaded.sendEpoch)
            assertEquals(state.sendCount, loaded.sendCount)
            assertEquals(state.sendEpochStartedAt, loaded.sendEpochStartedAt)
            assertEquals(state.sendEpochBaseEpoch, loaded.sendEpochBaseEpoch)
            assertEquals(state.highestPeAcked, loaded.highestPeAcked)
            assertEquals(state.lastResetSentAt, loaded.lastResetSentAt)
        }

    @Test
    fun applyOpenPersistsChainAdvanceSkippedKeysAndConsumption() =
        runTest {
            val peer = "peer0000"
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = RatchetEngine.RecvEpoch(epoch = 1, chainKey = ByteArray(32) { 8 }, next = 3, lastUsedAt = NOW),
                        skippedInserts =
                            listOf(
                                RatchetEngine.SkippedKey(epoch = 1, idx = 0, msgKey = ByteArray(32) { 10 }, createdAt = NOW),
                                RatchetEngine.SkippedKey(epoch = 1, idx = 1, msgKey = ByteArray(32) { 11 }, createdAt = NOW),
                            ),
                    ),
                headerSe = 1,
                headerN = 2,
            )
            assertEquals(3, repo.recvEpoch(peer, 1)?.next)
            assertArrayEquals(ByteArray(32) { 10 }, repo.skippedKey(peer, 1, 0))

            // Consuming skipped key (1, 0) deletes exactly that row.
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = RatchetEngine.RecvEpoch(epoch = 1, chainKey = ByteArray(32) { 8 }, next = 3, lastUsedAt = NOW),
                        consumedSkippedIdx = 0,
                    ),
                headerSe = 1,
                headerN = 0,
            )
            assertNull(repo.skippedKey(peer, 1, 0))
            assertNotNull(repo.skippedKey(peer, 1, 1))
        }

    @Test
    fun aReplacementPurgeDropsRecvStateButKeepsLocalEpochs() =
        runTest {
            val peer = "peer0000"
            repo.commitSend(session(peer), RatchetEngine.LocalEpoch(epoch = 1, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW))
            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = RatchetEngine.RecvEpoch(epoch = 1, chainKey = ByteArray(32), next = 1, lastUsedAt = NOW),
                        skippedInserts = listOf(RatchetEngine.SkippedKey(epoch = 1, idx = 5, msgKey = ByteArray(32), createdAt = NOW)),
                    ),
                headerSe = 1,
                headerN = 0,
            )

            repo.applyOpen(
                peerId = peer,
                delta =
                    RatchetEngine.OpenDelta(
                        session = session(peer),
                        recvEpoch = RatchetEngine.RecvEpoch(epoch = 1, chainKey = ByteArray(32) { 9 }, next = 1, lastUsedAt = NOW),
                        purgePeerRecvState = true,
                    ),
                headerSe = 1,
                headerN = 0,
            )
            // The purge dropped the old skipped key; the new-era recv row (written after the purge) and
            // our own epoch privs survive.
            assertNull(repo.skippedKey(peer, 1, 5))
            assertNotNull(repo.recvEpoch(peer, 1))
            assertNotNull(repo.localEpochPriv(peer, 1))
        }

    @Test
    fun sweepEnforcesTtlsAndTheSkippedKeyCap() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer))
            db.ratchetDao().insertSkippedKeys(
                listOf(
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 0, msgKey = ByteArray(32), createdAt = NOW - STALE),
                    RatchetSkippedKeyEntity(peer, epoch = 1, idx = 1, msgKey = ByteArray(32), createdAt = NOW),
                ),
            )
            db.ratchetDao().upsertRecvEpoch(
                app.getknit.knit.data.ratchet
                    .RatchetRecvEpochEntity(peer, epoch = 1, chainKey = ByteArray(32), next = 1, lastUsedAt = NOW - STALE),
            )

            repo.sweep(NOW)

            assertNull(repo.skippedKey(peer, 1, 0)) // past the 48 h TTL
            assertNotNull(repo.skippedKey(peer, 1, 1)) // fresh
            assertNull(repo.recvEpoch(peer, 1)) // past the 48 h TTL
        }

    @Test
    fun sweepRetiresAckedLocalEpochsButKeepsTheNewestThree() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer, sendEpoch = 6, highestPeAcked = 6))
            (1..6).forEach { epoch ->
                db.ratchetDao().insertLocalEpoch(
                    RatchetLocalEpochEntity(peer, epoch = epoch, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW - STALE),
                )
            }

            repo.sweep(NOW)

            // Epochs 1–3 are superseded + acked + past 48 h → retired (the PFS window closing);
            // the newest three always survive.
            assertNull(repo.localEpochPriv(peer, 1))
            assertNull(repo.localEpochPriv(peer, 3))
            assertNotNull(repo.localEpochPriv(peer, 4))
            assertNotNull(repo.localEpochPriv(peer, 6))
        }

    @Test
    fun deletePeerDropsEverything() =
        runTest {
            val peer = "peer0000"
            repo.upsertSession(session(peer))
            repo.commitSend(session(peer), RatchetEngine.LocalEpoch(epoch = 1, priv = ByteArray(32), pub = ByteArray(32), createdAt = NOW))
            repo.deletePeer(peer)
            assertNull(repo.session(peer))
            assertNull(repo.localEpochPriv(peer, 1))
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val STALE = 49 * 60 * 60_000L // just past the 48 h retention windows
    }
}
