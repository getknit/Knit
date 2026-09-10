package app.getknit.knit.data.draft

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
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

    @Upsert
    suspend fun upsert(row: DraftEntity)

    @Query("DELETE FROM drafts WHERE conversationId = :conversationId")
    suspend fun deleteFor(conversationId: String)
}
