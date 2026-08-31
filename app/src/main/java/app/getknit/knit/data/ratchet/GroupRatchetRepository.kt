package app.getknit.knit.data.ratchet

import app.getknit.knit.mesh.crypto.ratchet.GroupKeySendState
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore

/**
 * Room-backed [GroupRatchetStore]. Pure row mapping plus the retention sweep; **no transactions
 * here** — the callers (via `GroupRatchetSessions`) wrap the mutating methods in the same
 * `db.withWriteTransaction` as the surrounding mutation (the interface contract, matching
 * [RatchetRepository]).
 *
 * The sweep is the group forward-secrecy enforcement point (docs/GROUP_FORWARD_SECRECY.md §10):
 * what it deletes is what a later device compromise can no longer decrypt.
 */
class GroupRatchetRepository(
    private val dao: GroupRatchetDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : GroupRatchetStore {
    override suspend fun sendChain(groupId: String): GroupRatchetEngine.SendChain? = dao.newestSendChain(groupId)?.toChain()

    override suspend fun sendChains(groupId: String): List<GroupRatchetEngine.SendChain> = dao.sendChains(groupId).map { it.toChain() }

    override suspend fun commitSend(chain: GroupRatchetEngine.SendChain) {
        dao.upsertSendChain(
            GroupSendChainEntity(
                groupId = chain.groupId,
                epoch = chain.epoch,
                seed = chain.seed,
                chainKey = chain.chainKey,
                count = chain.count,
                mintedAt = chain.mintedAt,
                export = chain.export,
                updatedAt = clock(),
            ),
        )
    }

    override suspend fun deleteSendChains(groupId: String) {
        dao.deleteSendChainsFor(groupId)
    }

    override suspend fun recvChains(
        groupId: String,
        senderId: String,
        epoch: Int,
    ): List<GroupRatchetEngine.RecvChain> = dao.recvChains(groupId, senderId, epoch).map { it.toChain() }

    override suspend fun skippedKeys(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    ): List<GroupRatchetEngine.SkippedKey> =
        dao.skippedKeys(groupId, senderId, epoch, idx).map {
            GroupRatchetEngine.SkippedKey(
                epoch = it.epoch,
                mintedAt = it.mintedAt,
                idx = it.idx,
                msgKey = it.msgKey,
                createdAt = it.createdAt,
            )
        }

    override suspend fun insertRecvChain(chain: GroupRatchetEngine.RecvChain) {
        dao.upsertRecvChain(chain.toEntity())
    }

    override suspend fun applyOpen(
        groupId: String,
        senderId: String,
        delta: GroupRatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    ) {
        delta.recvChain?.let { dao.upsertRecvChain(it.toEntity()) }
        if (delta.skippedInserts.isNotEmpty()) {
            dao.insertSkippedKeys(
                delta.skippedInserts.map {
                    GroupSkippedKeyEntity(
                        groupId = groupId,
                        senderId = senderId,
                        epoch = it.epoch,
                        mintedAt = it.mintedAt,
                        idx = it.idx,
                        msgKey = it.msgKey,
                        createdAt = it.createdAt,
                    )
                },
            )
        }
        if (delta.consumedSkippedIdx != null) dao.deleteSkippedKeysAt(groupId, senderId, headerSe, headerN)
    }

    override suspend fun purgeGroup(groupId: String) {
        dao.deleteSendChainsFor(groupId)
        dao.deleteRecvChainsFor(groupId)
        dao.deleteSkippedKeysFor(groupId)
        dao.deleteKeySendsFor(groupId)
    }

    override suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendState? = dao.keySend(groupId, memberId)?.toState()

    override suspend fun keySends(groupId: String): List<GroupKeySendState> = dao.keySends(groupId).map { it.toState() }

    override suspend fun markKeySent(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    ) {
        val existing = dao.keySend(groupId, memberId)
        dao.upsertKeySend(
            GroupKeySendEntity(
                groupId = groupId,
                memberId = memberId,
                sentEpoch = maxOf(epoch, existing?.sentEpoch ?: 0),
                sentAt = at,
                ackedEpoch = existing?.ackedEpoch ?: 0,
                ackedAt = existing?.ackedAt ?: 0L,
            ),
        )
    }

    override suspend fun markKeyAcked(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    ) {
        val existing = dao.keySend(groupId, memberId) ?: return
        if (epoch <= existing.ackedEpoch) return
        dao.upsertKeySend(existing.copy(ackedEpoch = epoch, ackedAt = at))
    }

    override suspend fun deleteKeySend(
        groupId: String,
        memberId: String,
    ) {
        dao.deleteKeySend(groupId, memberId)
    }

    override suspend fun sweep(now: Long) {
        dao.deleteSkippedKeysBefore(now - SKIPPED_TTL_MS)
        val excess = dao.countSkippedKeys() - MAX_SKIPPED_KEYS
        if (excess > 0) dao.evictOldestSkippedKeys(excess)
        dao.deleteRecvChainsBefore(now - RECV_CHAIN_TTL_MS)
        dao.sendChainGroupIds().forEach { groupId -> sweepSendChains(groupId, now) }
    }

    /**
     * Retires superseded send epochs for [groupId] — the sender-side PFS window. The newest chain
     * always survives (it is the sealing epoch and the re-distribution source); the previous one
     * drains for [PREV_SEED_RETAIN_MS] past the newest's mint (key-request recovery of frames still
     * re-serving from custody), then everything below the newest goes.
     */
    private suspend fun sweepSendChains(
        groupId: String,
        now: Long,
    ) {
        val chains = dao.sendChains(groupId)
        val newest = chains.firstOrNull() ?: return
        val keepPrev = now - newest.mintedAt < PREV_SEED_RETAIN_MS
        val minEpoch = if (keepPrev) newest.epoch - 1 else newest.epoch
        if (chains.any { it.epoch < minEpoch }) dao.deleteSendChainsBelow(groupId, minEpoch)
    }

    private fun GroupSendChainEntity.toChain(): GroupRatchetEngine.SendChain =
        GroupRatchetEngine.SendChain(
            groupId = groupId,
            epoch = epoch,
            seed = seed,
            chainKey = chainKey,
            count = count,
            mintedAt = mintedAt,
            export = export,
        )

    private fun GroupRecvChainEntity.toChain(): GroupRatchetEngine.RecvChain =
        GroupRatchetEngine.RecvChain(
            groupId = groupId,
            senderId = senderId,
            epoch = epoch,
            mintedAt = mintedAt,
            chainKey = chainKey,
            next = next,
            lastUsedAt = lastUsedAt,
        )

    private fun GroupRatchetEngine.RecvChain.toEntity(): GroupRecvChainEntity =
        GroupRecvChainEntity(
            groupId = groupId,
            senderId = senderId,
            epoch = epoch,
            mintedAt = mintedAt,
            chainKey = chainKey,
            next = next,
            lastUsedAt = lastUsedAt,
        )

    private fun GroupKeySendEntity.toState(): GroupKeySendState =
        GroupKeySendState(
            groupId = groupId,
            memberId = memberId,
            sentEpoch = sentEpoch,
            sentAt = sentAt,
            ackedEpoch = ackedEpoch,
            ackedAt = ackedAt,
        )

    companion object {
        /** Skipped-key + recv-chain retention: 2x the 24 h custody TTL — mirrors the DM sweep. */
        const val SKIPPED_TTL_MS = 48 * 60 * 60_000L
        const val RECV_CHAIN_TTL_MS = 48 * 60 * 60_000L

        /** Global group skipped-key cap — its own budget, deliberately separate from the DM's 2000. */
        const val MAX_SKIPPED_KEYS = 2_000

        /** How long a superseded send seed stays recoverable via key-request (the DM `PREV_ROOT_TTL_MS` analogue). */
        const val PREV_SEED_RETAIN_MS = 48 * 60 * 60_000L
    }
}
