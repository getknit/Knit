package app.getknit.knit.data.ratchet

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * Ratchet-state queries. Unlike the carry store there is no convergence constraint here — this state is
 * strictly per-device — so retention sweeps are plain local GC. All quantities are tiny (sessions = DM
 * peers; epochs/skipped keys bounded by `RatchetRepository`'s caps).
 */
@Dao
@Suppress("TooManyFunctions") // small focused queries over four tables; splitting would obscure
interface RatchetDao {
    // --- sessions ---

    @Query("SELECT * FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun session(peerId: String): RatchetSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(row: RatchetSessionEntity)

    @Query("SELECT peerId FROM ratchet_sessions")
    suspend fun sessionPeerIds(): List<String>

    @Query("DELETE FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun deleteSession(peerId: String)

    // --- our epoch keypairs ---

    @Query("SELECT priv FROM ratchet_local_epochs WHERE peerId = :peerId AND epoch = :epoch")
    suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray?

    // REPLACE, not IGNORE: epoch numbering restarts at 1 when a session reset re-initiates, so a
    // re-minted number can collide with a surviving dead-era row. The live era must win — keeping the
    // dead key makes every peer frame based on the fresh epoch fail its AEAD with nothing to see in the
    // header. The frames the old key could still have served are pre-reset ciphertext, already stranded
    // by the re-root itself.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocalEpoch(row: RatchetLocalEpochEntity)

    // Newest = most recently MINTED, never the highest number. Epoch numbers restart on a session
    // reset, so a long-lived session banks high-numbered dead-era rows that outrank every live-era row
    // forever under numeric order — the sweep then keeps 16 dead keys and deletes each fresh epoch
    // within one cycle of minting it, wedging the pair in a reset-relapse loop (ADR 027). The epoch
    // tiebreak only orders rows minted in the same millisecond.
    @Query("SELECT * FROM ratchet_local_epochs WHERE peerId = :peerId ORDER BY createdAt DESC, epoch DESC")
    suspend fun localEpochsNewestFirst(peerId: String): List<RatchetLocalEpochEntity>

    @Query("DELETE FROM ratchet_local_epochs WHERE peerId = :peerId AND epoch IN (:epochs)")
    suspend fun deleteLocalEpochs(
        peerId: String,
        epochs: List<Int>,
    )

    @Query("DELETE FROM ratchet_local_epochs WHERE peerId = :peerId")
    suspend fun deleteLocalEpochsFor(peerId: String)

    // --- inbound epoch chains ---

    @Query("SELECT * FROM ratchet_recv_epochs WHERE peerId = :peerId AND epoch = :epoch")
    suspend fun recvEpoch(
        peerId: String,
        epoch: Int,
    ): RatchetRecvEpochEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecvEpoch(row: RatchetRecvEpochEntity)

    @Query("DELETE FROM ratchet_recv_epochs WHERE lastUsedAt < :cutoff")
    suspend fun deleteRecvEpochsBefore(cutoff: Long): Int

    @Query("DELETE FROM ratchet_recv_epochs WHERE peerId = :peerId")
    suspend fun deleteRecvEpochsFor(peerId: String)

    // --- skipped message keys ---

    @Query("SELECT msgKey FROM ratchet_skipped_keys WHERE peerId = :peerId AND epoch = :epoch AND idx = :idx")
    suspend fun skippedKey(
        peerId: String,
        epoch: Int,
        idx: Int,
    ): ByteArray?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkippedKeys(rows: List<RatchetSkippedKeyEntity>)

    @Query("DELETE FROM ratchet_skipped_keys WHERE peerId = :peerId AND epoch = :epoch AND idx = :idx")
    suspend fun deleteSkippedKey(
        peerId: String,
        epoch: Int,
        idx: Int,
    )

    @Query("DELETE FROM ratchet_skipped_keys WHERE createdAt < :cutoff")
    suspend fun deleteSkippedKeysBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM ratchet_skipped_keys")
    suspend fun countSkippedKeys(): Int

    /** Global-cap pressure valve: drop the [n] oldest skipped keys (they were the least likely to arrive). */
    @Query(
        "DELETE FROM ratchet_skipped_keys WHERE (peerId, epoch, idx) IN " +
            "(SELECT peerId, epoch, idx FROM ratchet_skipped_keys ORDER BY createdAt ASC LIMIT :n)",
    )
    suspend fun evictOldestSkippedKeys(n: Int)

    @Query("DELETE FROM ratchet_skipped_keys WHERE peerId = :peerId")
    suspend fun deleteSkippedKeysFor(peerId: String)
}
