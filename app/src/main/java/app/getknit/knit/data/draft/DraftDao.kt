package app.getknit.knit.data.draft

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Row-level operations for composer drafts. Thin by rule (`.agents/rules/coding.md`): no `@Transaction`
 * methods — a draft is a single row read and written on its own, and [DraftRepository] serializes the
 * writes.
 */
@Dao
interface DraftDao {
    /** The text left in [conversationId]'s composer, or null if there is none. */
    @Query("SELECT text FROM drafts WHERE conversationId = :conversationId")
    suspend fun find(conversationId: String): String?

    /** Every draft on the device, for the chat list's per-row "Draft: …" preview. */
    @Query("SELECT * FROM drafts")
    fun observeAll(): Flow<List<DraftEntity>>

    /**
     * Writes [conversationId]'s draft, replacing the text and stamp in place when the row is already there.
     *
     * Spelled out rather than `@Upsert` on purpose: Room implements that annotation as an INSERT it expects
     * to fail on an existing key, then an UPDATE — and SQLCipher logs every failed statement at `E` before
     * Room catches it, so a typed sentence used to leave one "UNIQUE constraint failed" line per pause in
     * logcat (work item 60). `ON CONFLICT DO UPDATE` never issues a failing statement, and unlike
     * `OnConflictStrategy.REPLACE` it updates the row rather than deleting and re-inserting it.
     */
    @Query(
        "INSERT INTO drafts (conversationId, text, updatedAt) VALUES (:conversationId, :text, :updatedAt) " +
            "ON CONFLICT(conversationId) DO UPDATE SET text = excluded.text, updatedAt = excluded.updatedAt",
    )
    suspend fun upsert(
        conversationId: String,
        text: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM drafts WHERE conversationId = :conversationId")
    suspend fun deleteFor(conversationId: String)
}
