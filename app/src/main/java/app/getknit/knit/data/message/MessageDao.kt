package app.getknit.knit.data.message

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

// A data-access interface: one method per query, so the count naturally exceeds detekt's interface limit.
@Suppress("TooManyFunctions")
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY sentAt ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    /**
     * The whole stored row for [id], or null once it is gone — lets the message-details screen follow a
     * single message (and close itself when that message is deleted out from under it).
     */
    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeById(id: String): Flow<MessageEntity?>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    /**
     * Inserts [message] unless a row with its id already exists, returning the new rowid or -1 when it was
     * left alone. The inbound delivery path's write: a re-served frame is the same signed bytes, so the
     * first row written for an id is the only one that ever should be (it keeps the plane the message first
     * arrived on and whatever was added to the row since — see `InboundPipeline.deliverChat`).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    /**
     * The [MessageEntity.recipientId] of the stored message [id], or null when it's a broadcast/group
     * message OR no such message is held. Lets [markReceived] reject a receipt whose sender isn't the
     * message's addressed DM recipient (a forged-ack guard); broadcast/group keep the best-effort tick.
     */
    @Query("SELECT recipientId FROM messages WHERE id = :id")
    suspend fun recipientOf(id: String): String?

    /** The conversation the stored message [id] belongs to, or null when it isn't held. */
    @Query("SELECT conversationId FROM messages WHERE id = :id")
    suspend fun conversationOf(id: String): String?

    /**
     * Flips the delivery tick for [id], recording the [DeliveryPlane] code ([via]) the receipt that did it
     * arrived on. Callers pass the enum through [app.getknit.knit.data.MessageRepository.markReceived].
     *
     * Stays idempotent (a receipt is re-served routinely), and the plane is **first-evidence-wins**: the
     * `CASE` reads the pre-update `received`, so only the receipt that actually flips the tick sets the
     * plane. A duplicate crossing later on another plane leaves the mark alone — it describes how the
     * message first got there, not every route it has since travelled.
     */
    @Query(
        "UPDATE messages SET " +
            "receivedVia = CASE WHEN received = 0 THEN :via ELSE receivedVia END, received = 1 " +
            "WHERE id = :id",
    )
    suspend fun markReceived(
        id: String,
        via: Int,
    )

    /** How many messages in [conversationId] were authored by [me] — nonzero means the user has replied there. */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND senderId = :me")
    suspend fun countMineIn(
        conversationId: String,
        me: String,
    ): Int

    /** Outgoing DMs to [recipientId] saved while their key was unknown, awaiting retransmit on key arrival. */
    @Query("SELECT * FROM messages WHERE recipientId = :recipientId AND pendingKey = 1")
    suspend fun pendingForRecipient(recipientId: String): List<MessageEntity>

    /**
     * Our own recent DMs to [recipientId] that were flooded but never acked — the re-seal set when the
     * peer resets its ratchet session (a wiped device can no longer open what we sealed to the old
     * session, but custody still holds those frames; re-sealing under the fresh session recovers them).
     * Bounded by [since] (the custody TTL) — anything older is gone from the mesh anyway.
     */
    @Query(
        "SELECT * FROM messages WHERE recipientId = :recipientId AND senderId = :me " +
            "AND received = 0 AND pendingKey = 0 AND sentAt >= :since",
    )
    suspend fun unackedDmsTo(
        recipientId: String,
        me: String,
        since: Long,
    ): List<MessageEntity>

    /** Clears the [MessageEntity.pendingKey] flag once a stuck DM has been sealed and flooded. */
    @Query("UPDATE messages SET pendingKey = 0 WHERE id = :id")
    suspend fun clearPending(id: String)

    /** Removes a single message locally (used by the long-press "Delete message" action). */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Removes every message in a thread (used when leaving a group, so the thread vanishes). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** How many messages still reference [hash] — guards deleting a shared, content-addressed blob. */
    @Query("SELECT COUNT(*) FROM messages WHERE attachmentHash = :hash")
    suspend fun countByAttachmentHash(hash: String): Int

    /**
     * The (base64) per-attachment key stored with a message referencing the ciphertext [hash], if any —
     * used to decrypt a just-pulled E2E attachment blob so its plaintext can be screened. Null for a
     * plaintext (broadcast) attachment or when no such message is stored (e.g. we only relayed the blob).
     */
    @Query(
        "SELECT attachmentKey FROM messages " +
            "WHERE attachmentHash = :hash AND attachmentKey IS NOT NULL LIMIT 1",
    )
    suspend fun attachmentKeyForHash(hash: String): String?

    /**
     * The MIME of any stored message referencing the ciphertext [hash] — how the inbound derivation decides
     * whether a just-pulled blob is a voice note without decrypting it first. Null when no message row names
     * the hash (a relayed blob, or an avatar, which writes no message row at all).
     */
    @Query(
        "SELECT attachmentMime FROM messages " +
            "WHERE attachmentHash = :hash AND attachmentMime IS NOT NULL LIMIT 1",
    )
    suspend fun attachmentMimeForHash(hash: String): String?

    /**
     * Records the locally-derived voice-note description for every message naming the ciphertext [hash].
     * Keyed by hash rather than message id because the same voice note can be quoted into more than one row
     * (a re-send, or a forward), and each of those bubbles needs the same waveform — deriving once and
     * writing across them all is why this is content-addressed like the blob itself.
     */
    @Query("UPDATE messages SET voiceDurationMs = :durationMs, voicePeaks = :peaks WHERE attachmentHash = :hash")
    suspend fun setVoiceMeta(
        hash: String,
        durationMs: Int?,
        peaks: String?,
    )

    /** Attachment hashes referenced by stored messages whose bytes aren't in the `blobs` table yet. */
    @Query(
        "SELECT DISTINCT attachmentHash FROM messages " +
            "WHERE attachmentHash IS NOT NULL AND attachmentHash NOT IN (SELECT hash FROM blobs)",
    )
    suspend fun hashesNeedingFetch(): List<String>

    /**
     * Whether a message [me] authored names the ciphertext [hash] and has been acked — the delivery
     * evidence `AttachmentDeferPolicy` needs before holding an upload back from the Internet plane. A
     * hash we hold no *authored* row for (a relayed attachment, or an avatar, which writes no message
     * row at all) reads false, which is the safe answer: it pushes.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages " +
            "WHERE attachmentHash = :hash AND senderId = :me AND received = 1)",
    )
    suspend fun attachmentAcked(
        hash: String,
        me: String,
    ): Boolean

    /** Distinct conversations the local user ([me]) has authored a message in — the "threads I started" signal. */
    @Query("SELECT DISTINCT conversationId FROM messages WHERE senderId = :me")
    suspend fun conversationsIAuthoredIn(me: String): List<String>

    /** Every distinct conversation id with any message — the candidate set for counting pending requests. */
    @Query("SELECT DISTINCT conversationId FROM messages")
    suspend fun distinctConversations(): List<String>

    /**
     * Distinct node ids that have sent a message in [conversationId] — a group is "known" once one is.
     *
     * Status notices are excluded (`kind = 0` is [MessageEntity.KIND_NORMAL]; Room's `@Query` can't
     * reference the constant). A notice's `senderId` is the event's **subject**, not an author, so
     * counting it here would let a peer who has never spoken — one who merely renamed themselves, or
     * left — satisfy the "a known peer has posted here" half of [Conversations.isAccepted] and quietly
     * promote a message request into an accepted chat.
     */
    @Query("SELECT DISTINCT senderId FROM messages WHERE conversationId = :conversationId AND kind = 0")
    suspend fun sendersIn(conversationId: String): List<String>

    /**
     * Whether [conversationId] holds any ordinary message (`kind = 0`, [MessageEntity.KIND_NORMAL]) —
     * the gate for writing a peer status notice into a DM thread. A `profile` frame floods the whole
     * mesh, so without this a stranger's rename would conjure a thread into the chat list; status rows
     * don't count, or one notice would license the next.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId = :conversationId AND kind = 0)")
    suspend fun hasMessagesIn(conversationId: String): Boolean

    /** Per-conversation row count + newest sentAt, for the retention sweep's cap / age / thread-count decisions. */
    @Query("SELECT conversationId, MAX(sentAt) AS lastSentAt, COUNT(*) AS count FROM messages GROUP BY conversationId")
    suspend fun conversationActivity(): List<ConversationActivity>

    /** Keeps only the newest [keep] messages (by sentAt) in [conversationId], deleting the rest. */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId AND id NOT IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY sentAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun deleteOldestInConversation(
        conversationId: String,
        keep: Int,
    )

    /** Deletes messages in [conversationId] older than [cutoff] (frame-global sentAt). */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND sentAt < :cutoff")
    suspend fun deleteOlderThan(
        conversationId: String,
        cutoff: Long,
    )
}

/** Room projection for [MessageDao.conversationActivity]: a thread's id, newest message time, and row count. */
data class ConversationActivity(
    val conversationId: String,
    val lastSentAt: Long,
    val count: Int,
)
