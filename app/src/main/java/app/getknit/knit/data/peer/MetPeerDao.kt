package app.getknit.knit.data.peer

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetPeerDao {
    /** Inserts a first sighting; a node already met keeps its row (and its `firstMetAt`) untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: MetPeerEntity)

    @Query("UPDATE met_peers SET lastMetAt = :now WHERE nodeId = :nodeId")
    suspend fun touch(
        nodeId: String,
        now: Long,
    )

    @Query("SELECT COUNT(*) FROM met_peers")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM met_peers")
    suspend fun count(): Int

    @Query("SELECT * FROM met_peers WHERE nodeId = :nodeId")
    suspend fun find(nodeId: String): MetPeerEntity?

    /** [find] as a flow, so a profile's "first met" line relights the moment a stranger becomes met. */
    @Query("SELECT * FROM met_peers WHERE nodeId = :nodeId")
    fun observe(nodeId: String): Flow<MetPeerEntity?>

    /** Evicts the [n] least-recently-met rows — the cap trims strangers seen once, never a regular contact. */
    @Query("DELETE FROM met_peers WHERE nodeId IN (SELECT nodeId FROM met_peers ORDER BY lastMetAt ASC, nodeId ASC LIMIT :n)")
    suspend fun evictOldest(n: Int)
}
