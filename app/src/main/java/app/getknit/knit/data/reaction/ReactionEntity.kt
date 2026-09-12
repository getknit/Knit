package app.getknit.knit.data.reaction

import androidx.room3.Entity
import androidx.room3.Index

/**
 * One person's reaction to one message, as stored on this device. The composite primary key
 * ([messageId], [reactorNodeId]) enforces "at most one reaction per person per message", so a
 * toggle/replace is a plain upsert.
 *
 * [emoji] null is a retraction *tombstone*, not a deleted row: a null at a newer [updatedAt] must
 * still beat a stale "add" frame that arrives late (last-writer-wins), which a delete couldn't do.
 * The UI stream filters these out (see [ReactionDao.observeAll]).
 *
 * [updatedAt] is the reacting device's wall clock at emit time (the wire [sentAt], bounded on the way
 * in to `Protocol.MAX_FUTURE_SKEW_MS` past our own clock so one far-future stamp cannot hold the row
 * forever); it is the last-writer-wins comparator applied in [ReactionRepository.apply]. There is intentionally no
 * foreign key to `messages`: a reaction can arrive before the message it targets (out-of-order mesh
 * delivery), and must persist as an orphan until that message shows up.
 */
@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "reactorNodeId"],
    indices = [Index("messageId")],
)
data class ReactionEntity(
    val messageId: String,
    val reactorNodeId: String,
    val emoji: String?,
    val updatedAt: Long,
)
