package app.getknit.knit.data.ratchet

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * Row-level operations for the group sender-key ratchet state (crypto scheme v2, group form). Thin by rule
 * (`.agents/rules/coding.md`): no `@Transaction` methods — atomicity lives at the repository callers'
 * `db.withTransaction`.
 */
@Suppress("TooManyFunctions") // small focused queries over four tables; splitting would obscure
@Dao
interface GroupRatchetDao {
    // --- our send chains ---

    @Query("SELECT * FROM group_send_chains WHERE groupId = :groupId ORDER BY epoch DESC LIMIT 1")
    suspend fun newestSendChain(groupId: String): GroupSendChainEntity?

    @Query("SELECT * FROM group_send_chains WHERE groupId = :groupId ORDER BY epoch DESC")
    suspend fun sendChains(groupId: String): List<GroupSendChainEntity>

    @Query("SELECT DISTINCT groupId FROM group_send_chains")
    suspend fun sendChainGroupIds(): List<String>

    @Upsert
    suspend fun upsertSendChain(row: GroupSendChainEntity)

    @Query("DELETE FROM group_send_chains WHERE groupId = :groupId")
    suspend fun deleteSendChainsFor(groupId: String)

    @Query("DELETE FROM group_send_chains WHERE groupId = :groupId AND epoch < :minEpoch")
    suspend fun deleteSendChainsBelow(
        groupId: String,
        minEpoch: Int,
    )

    // --- inbound per-sender chains ---

    @Query(
        "SELECT * FROM group_recv_chains WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch " +
            "ORDER BY mintedAt DESC",
    )
    suspend fun recvChains(
        groupId: String,
        senderId: String,
        epoch: Int,
    ): List<GroupRecvChainEntity>

    @Upsert
    suspend fun upsertRecvChain(row: GroupRecvChainEntity)

    @Query("DELETE FROM group_recv_chains WHERE lastUsedAt < :cutoff")
    suspend fun deleteRecvChainsBefore(cutoff: Long)

    @Query("DELETE FROM group_recv_chains WHERE groupId = :groupId")
    suspend fun deleteRecvChainsFor(groupId: String)

    // --- skipped keys ---

    @Query(
        "SELECT * FROM group_skipped_keys WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch " +
            "AND idx = :idx ORDER BY mintedAt DESC",
    )
    suspend fun skippedKeys(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    ): List<GroupSkippedKeyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkippedKeys(rows: List<GroupSkippedKeyEntity>)

    /** Deletes every mint era's stored key at one chain position (consumption — see the store kdoc). */
    @Query("DELETE FROM group_skipped_keys WHERE groupId = :groupId AND senderId = :senderId AND epoch = :epoch AND idx = :idx")
    suspend fun deleteSkippedKeysAt(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    )

    @Query("DELETE FROM group_skipped_keys WHERE createdAt < :cutoff")
    suspend fun deleteSkippedKeysBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM group_skipped_keys")
    suspend fun countSkippedKeys(): Int

    @Query(
        "DELETE FROM group_skipped_keys WHERE (groupId, senderId, epoch, mintedAt, idx) IN " +
            "(SELECT groupId, senderId, epoch, mintedAt, idx FROM group_skipped_keys ORDER BY createdAt ASC LIMIT :n)",
    )
    suspend fun evictOldestSkippedKeys(n: Int)

    @Query("DELETE FROM group_skipped_keys WHERE groupId = :groupId")
    suspend fun deleteSkippedKeysFor(groupId: String)

    // --- the seed outbox ---

    @Query("SELECT * FROM group_key_sends WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendEntity?

    @Query("SELECT * FROM group_key_sends WHERE groupId = :groupId")
    suspend fun keySends(groupId: String): List<GroupKeySendEntity>

    @Upsert
    suspend fun upsertKeySend(row: GroupKeySendEntity)

    @Query("DELETE FROM group_key_sends WHERE groupId = :groupId AND memberId = :memberId")
    suspend fun deleteKeySend(
        groupId: String,
        memberId: String,
    )

    @Query("DELETE FROM group_key_sends WHERE groupId = :groupId")
    suspend fun deleteKeySendsFor(groupId: String)
}
