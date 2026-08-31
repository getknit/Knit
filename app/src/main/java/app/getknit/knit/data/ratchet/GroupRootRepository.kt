package app.getknit.knit.data.ratchet

import app.getknit.knit.mesh.spool.GroupRootState
import app.getknit.knit.mesh.spool.GroupRootStore

/**
 * Room-backed [GroupRootStore] for the spool plane's shared group roots (`docs/SPOOL_PROTOCOL.md` §3.2).
 * Pure row mapping plus the drain sweep; **no transactions here** — callers wrap the mutating methods in
 * the same `db.withWriteTransaction` as the surrounding mutation, matching [GroupRatchetRepository].
 *
 * [markEligible] and [markRemintDue] read-modify-write on purpose: both are idempotent stamps that must
 * NOT move once set. Re-stamping [GroupRootState.firstEligibleAt] would restart the mint grace on every
 * pass and the device would never mint; re-stamping [GroupRootState.remintDueAt] would let a re-served
 * `groupleave` push the rotation deadline back indefinitely.
 */
class GroupRootRepository(
    private val dao: GroupRootDao,
) : GroupRootStore {
    override suspend fun find(groupId: String): GroupRootState? = dao.find(groupId)?.toState()

    override suspend fun all(): List<GroupRootState> = dao.all().map { it.toState() }

    override suspend fun upsert(state: GroupRootState) {
        dao.upsert(
            GroupRootEntity(
                groupId = state.groupId,
                root = state.root,
                version = state.version,
                minter = state.minter,
                prevRoot = state.prevRoot,
                prevVersion = state.prevVersion,
                prevExpiresAt = state.prevExpiresAt,
                firstEligibleAt = state.firstEligibleAt,
                remintDueAt = state.remintDueAt,
            ),
        )
    }

    override suspend fun markEligible(
        groupId: String,
        at: Long,
    ) {
        val row = dao.find(groupId)
        when {
            row == null -> dao.upsert(GroupRootEntity(groupId = groupId, firstEligibleAt = at))
            row.firstEligibleAt <= 0L -> dao.upsert(row.copy(firstEligibleAt = at))
            else -> Unit
        }
    }

    override suspend fun markRemintDue(
        groupId: String,
        at: Long,
    ) {
        val row = dao.find(groupId) ?: return
        if (row.root == null || row.remintDueAt > 0L) return
        dao.upsert(row.copy(remintDueAt = at))
    }

    override suspend fun purge(groupId: String) {
        dao.deleteFor(groupId)
    }

    override suspend fun sweep(now: Long) {
        dao.clearDrainedPrevRoots(now)
    }
}

private fun GroupRootEntity.toState() =
    GroupRootState(
        groupId = groupId,
        root = root,
        version = version,
        minter = minter,
        prevRoot = prevRoot,
        prevVersion = prevVersion,
        prevExpiresAt = prevExpiresAt,
        firstEligibleAt = firstEligibleAt,
        remintDueAt = remintDueAt,
    )
