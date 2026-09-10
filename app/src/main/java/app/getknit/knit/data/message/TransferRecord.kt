package app.getknit.knit.data.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a direct Wi-Fi file transfer stands. One vocabulary for the live state (`transfer/TransferManager`),
 * the persisted [TransferRecord] and the chat card. [Offered] is the same phase seen from both sides — the
 * offer is out and unanswered — and `outgoing` says which side this is. Names are stored in the row's JSON,
 * so this enum is append-only and never renamed.
 */
enum class TransferPhase {
    Offered,
    Connecting,
    Transferring,
    Done,
    Declined,
    Expired,
    Cancelled,
    Failed,
    ;

    /** True once nothing more can happen to the transfer. */
    val terminal: Boolean
        get() = this == Done || this == Declined || this == Expired || this == Cancelled || this == Failed
}

/**
 * What a [MessageEntity.KIND_FILE_TRANSFER] row's [MessageEntity.body] records: the file that was offered
 * ([name], [size], [mime]), which side offered it, how far it got, and — once received — where it was saved.
 * Stored as a small JSON object, the [PeerRename] convention, so the row needs no new column. Deliberately
 * never carries the Wi-Fi credentials or the stream key: those live in memory for one transfer and die with it.
 *
 * Progress is not here either — the row is rewritten only on a phase change, and the card reads live bytes
 * from the manager's state. A non-terminal record with no live state (the process died mid-transfer) is
 * what the card draws as interrupted.
 */
@Serializable
data class TransferRecord(
    val id: String,
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String? = null,
    val phase: TransferPhase,
    val savedUri: String? = null,
    val reason: Int? = null,
) {
    fun encode(): String = json.encodeToString(this)

    /**
     * The chat row for this record in the DM with [peerId]: a status-notice-shaped row (`received = true`, no
     * tick, out of unread counts) whose sender is whoever offered the file. The id is deterministic per
     * transfer, so every phase change is an upsert of the same row — and [sentAt] stays the offer's time
     * through all of them, so a thread does not jump up the chat list each time a phase turns over.
     *
     * It is the one notice kind the chat list *does* preview: unlike a rename, its sender is a real author
     * and its subject is the conversation itself (`ui/chat/transferPreview`).
     */
    fun toEntity(
        peerId: String,
        selfId: String,
        sentAt: Long,
    ): MessageEntity =
        MessageEntity(
            id = rowId(id),
            senderId = if (outgoing) selfId else peerId,
            recipientId = if (outgoing) peerId else selfId,
            conversationId = peerId,
            body = encode(),
            sentAt = sentAt,
            received = true,
            kind = MessageEntity.KIND_FILE_TRANSFER,
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The `messages.id` of the record for transfer [transferId]. */
        fun rowId(transferId: String): String = "xfer:$transferId"

        /** Reads [body]; null for anything that is not an encoded record (a row this build cannot read). */
        fun decode(body: String): TransferRecord? = runCatching { json.decodeFromString<TransferRecord>(body) }.getOrNull()
    }
}
