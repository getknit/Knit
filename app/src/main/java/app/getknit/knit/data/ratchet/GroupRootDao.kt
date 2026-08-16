package app.getknit.knit.data.ratchet

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Row-level operations for the spool plane's shared group roots. Thin by rule
 * (`.agents/rules/coding.md`): no `@Transaction` methods — atomicity lives at the repository callers'
 * `db.withTransaction`.
 */
@Dao
interface GroupRootDao {
    @Query("SELECT * FROM group_roots WHERE groupId = :groupId")
    suspend fun find(groupId: String): GroupRootEntity?

    @Query("SELECT * FROM group_roots")
    suspend fun all(): List<GroupRootEntity>

    @Upsert
    suspend fun upsert(row: GroupRootEntity)

    @Query("DELETE FROM group_roots WHERE groupId = :groupId")
    suspend fun deleteFor(groupId: String)

    /** Retires a drained previous lineage in place; the live root is untouched. */
    @Query(
        "UPDATE group_roots SET prevRoot = NULL, prevVersion = 0, prevExpiresAt = 0 " +
            "WHERE prevExpiresAt != 0 AND prevExpiresAt <= :now",
    )
    suspend fun clearDrainedPrevRoots(now: Long)
}
