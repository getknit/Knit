package app.getknit.knit.data.message

import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions

/**
 * The full-text index over [MessageEntity.body] — the engine behind message search. An FTS4
 * *external-content* table: it stores no text of its own, only each body's tokens keyed by
 * `messages.rowid`, and the four `room_fts_content_sync_messages_fts_*` triggers Room generates keep it in
 * step with every INSERT, UPDATE and DELETE on `messages`. Nothing writes it directly, and nothing reads it
 * except [MessageDao.searchBodies], which is bounded and index-served (the `MessageDao` header rule).
 *
 * `unicode61` folds case and strips the Latin diacritics at index and query time, so `cafe` finds `Café`.
 * It knows no word boundaries inside a run of ideographs, so a CJK body is one token per run and only a
 * prefix of the run matches. Two invariants ride on `rowid`: `messages` has a TEXT primary key, so a
 * `VACUUM` may renumber its rowids and would silently desynchronise this index (never VACUUM; if one is
 * ever needed, follow it with `INSERT INTO messages_fts(messages_fts) VALUES('rebuild')`), and an
 * `INSERT OR REPLACE` on `messages` is a delete plus an insert under a fresh rowid whose implicit delete
 * fires the sync trigger only under `recursive_triggers` — the DAO uses `@Upsert` and `OR IGNORE`, never
 * REPLACE, and must keep to that.
 */
@Entity(tableName = "messages_fts")
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class MessageFtsEntity(
    val body: String,
)
