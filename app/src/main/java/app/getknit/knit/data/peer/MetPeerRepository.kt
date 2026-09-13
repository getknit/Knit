package app.getknit.knit.data.peer

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.KnitDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The phones this one has met — see [MetPeerEntity] for what "met" means and why it is its own table.
 *
 * [recordMet] is fed by `MeshManager.watchMetPeers`, which diffs the nearby set and hands over only the
 * newcomers, so a steady neighbor costs nothing and a peer that leaves and returns costs one row touch.
 * The whole write is one transaction (`rules/coding.md`): the insert-or-ignore, the `lastMetAt` touch and
 * the cap trim would otherwise interleave with another sighting's, and the trim's count-then-evict is a
 * check-then-act.
 */
class MetPeerRepository(
    private val dao: MetPeerDao,
    private val db: KnitDatabase,
    private val maxRows: Int = MAX_ROWS,
) {
    /** How many distinct phones have been met; re-emits on every first sighting. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Records that every id in [nodeIds] is in range now: a first sighting inserts, a repeat only touches. */
    suspend fun recordMet(
        nodeIds: Collection<String>,
        now: Long,
    ) {
        if (nodeIds.isEmpty()) return
        db.withWriteTransaction {
            nodeIds.forEach { id ->
                dao.insertIgnore(MetPeerEntity(nodeId = id, firstMetAt = now, lastMetAt = now))
                dao.touch(id, now)
            }
            val over = dao.count() - maxRows
            if (over > 0) dao.evictOldest(over)
        }
    }

    companion object {
        /**
         * A node id is an unauthenticated claim at sighting time (the neighbor set is populated from radio
         * adverts before any profile verifies), so the table is bounded like `PeerDao`'s cap — wide enough
         * that no honest phone ever reaches it, trimmed least-recently-met so it sheds one-time strangers.
         */
        const val MAX_ROWS = 10_000
    }
}
