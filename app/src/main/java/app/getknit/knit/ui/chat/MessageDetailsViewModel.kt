package app.getknit.knit.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One person's reaction to the message, resolved for display: who they are ([displayName], falling back
 * to a stable alias when we hold no profile for them), what they left ([emoji]), and when ([reactedAt],
 * the reacting device's clock as stored in `reactions.updatedAt`).
 */
data class ReactorRow(
    val nodeId: String,
    val displayName: String,
    val avatarHash: String?,
    val emoji: String,
    val reactedAt: Long,
    val isSelf: Boolean,
)

/** The stored row, carrying whether this screen has ever seen it — see [MessageDetailsUiState.vanished]. */
private data class MessageSighting(
    val row: MessageEntity? = null,
    val everSeen: Boolean = false,
)

/** One selectable emoji on the details screen's filter row — the chip's emoji and how many left it. */
data class ReactionFilter(
    val emoji: String,
    val count: Int,
)

/**
 * The message as shown on its details screen.
 *
 * [vanished] means the row was here and is now gone (deleted from the chat behind us, or reaped by
 * retention) — the screen's cue to close. It is deliberately **not** "the query returned nothing": a
 * deep link that lands before the row is written reads null once and would slam the screen shut on
 * arrival, which is exactly what a `demo_route` into a still-seeding build does.
 */
data class MessageDetailsUiState(
    val messageId: String,
    val vanished: Boolean = false,
    val body: String = "",
    val hasAttachment: Boolean = false,
    val moderationFlagged: Boolean = false,
    val mine: Boolean = false,
    val senderName: String = "",
    val senderNodeId: String = "",
    val sentAt: Long = 0L,
    val delivery: DeliveryStatus = DeliveryStatus.Sent,
    val plane: DeliveryPlane = DeliveryPlane.Unknown,
    val reactors: List<ReactorRow> = emptyList(),
    val filters: List<ReactionFilter> = emptyList(),
)

/**
 * Backs the message-details screen (keyed by [messageId]): who reacted and with what, plus the
 * per-message metadata the chat bubble has no room for — when it was sent, and how far it got.
 *
 * Read-only over rows this device already holds. The reactor identity the chat's
 * [ReactionSummary] tally aggregates away ("👍 3" says nothing about *which* three) is right there in
 * `reactions.reactorNodeId`; this just declines to throw it away, resolving each one through
 * [displayNameFor] so a reactor whose profile hasn't reached us still reads as a stable alias rather
 * than a raw node id.
 *
 * Reactions from blocked people are listed, not filtered: [ChatViewModel] filters blocked senders'
 * *messages* but never their reactions, so the chip this screen was opened from already counts them —
 * hiding them here would make the screen disagree with the chip.
 */
class MessageDetailsViewModel(
    private val messageId: String,
    messages: MessageRepository,
    reactions: ReactionRepository,
    peers: PeerRepository,
    settings: SettingsStore,
    identity: Identity,
) : ViewModel() {
    private val myNodeId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { myNodeId.value = identity.nodeId() }
    }

    // The row plus whether it has ever been seen, so a not-yet-written row is told apart from a deleted
    // one. `scan` carries that one bit forward; `drop(1)` discards the seed accumulator.
    private val sightings =
        messages
            .observeMessage(messageId)
            .scan(MessageSighting()) { previous, row -> MessageSighting(row, previous.everSeen || row != null) }
            .drop(1)

    val state: StateFlow<MessageDetailsUiState> =
        combine(
            sightings,
            reactions.observeReactionsFor(messageId),
            peers.observePeers(),
            myNodeId,
            settings.contentFilteringEnabled,
        ) { sighting, reacts, peerList, me, hideSensitive ->
            val message = sighting.row
            val everSeen = sighting.everSeen
            if (message == null) {
                MessageDetailsUiState(messageId = messageId, vanished = everSeen)
            } else {
                val peersByNode = peerList.associateBy { it.nodeId }
                // Chronological, matching the DAO's `updatedAt ASC` — the order people reacted in reads
                // better here than any ranking, and the filter chips carry the per-emoji grouping.
                val rows =
                    reacts.mapNotNull { reaction ->
                        val emoji = reaction.emoji ?: return@mapNotNull null
                        ReactorRow(
                            nodeId = reaction.reactorNodeId,
                            displayName = displayNameFor(peersByNode[reaction.reactorNodeId]?.name, reaction.reactorNodeId),
                            avatarHash = peersByNode[reaction.reactorNodeId]?.avatarHash,
                            emoji = emoji,
                            reactedAt = reaction.updatedAt,
                            isSelf = reaction.reactorNodeId == me,
                        )
                    }
                MessageDetailsUiState(
                    messageId = messageId,
                    body = message.body,
                    hasAttachment = message.attachmentHash != null,
                    moderationFlagged = hideSensitive && message.moderation == MessageEntity.MODERATION_TEXT_FLAGGED,
                    mine = message.senderId == me,
                    senderName = displayNameFor(peersByNode[message.senderId]?.name, message.senderId),
                    senderNodeId = message.senderId,
                    sentAt = message.sentAt,
                    delivery = DeliveryStatus.of(message),
                    plane = message.receivedPlane,
                    reactors = rows,
                    filters = filtersOf(rows),
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MessageDetailsUiState(messageId = messageId),
        )

    /** Chip order: most-reacted first, ties broken by who got there first, so chips don't reshuffle. */
    private fun filtersOf(rows: List<ReactorRow>): List<ReactionFilter> =
        rows
            .groupBy { it.emoji }
            .map { (emoji, group) -> ReactionFilter(emoji, group.size) to group.minOf { it.reactedAt } }
            .sortedWith(compareByDescending<Pair<ReactionFilter, Long>> { it.first.count }.thenBy { it.second })
            .map { it.first }
}
