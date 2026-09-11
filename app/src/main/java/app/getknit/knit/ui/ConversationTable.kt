package app.getknit.knit.ui

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the list screens know about every conversation at once — the chat list and the requests inbox —
 * read as per-thread summaries rather than as the messages table. Each field is one bounded query
 * (see `MessageDao`), so the cost of an emission is a few index walks plus one seek per thread, however
 * long any thread has grown; an accepted thread has no retention cap, so that is the only shape that
 * stays cheap.
 *
 * [heads] is the newest row of the asked kinds per conversation, from a sender not in [blocked] — the
 * row's preview, time, tick and sort key. [conversations] is every thread with at least one row of *any*
 * kind from an unblocked sender, which is a wider set: a thread holding only a notice has no head but is
 * still a thread (and, for a stranger's group, still a request). [groupSenders] is who has posted an
 * ordinary message in each group and [authored] is where we have — the two inputs `Conversations.isAccepted`
 * needs beyond the settings sets, read by the same rules the sweep and the notify gate use (`kind = 0`
 * senders; our own rows with no `originNode`), so the screens and the gate agree. [authored] is empty and
 * [me] null until our own id resolves.
 */
internal data class ConversationTable(
    val heads: Map<String, MessageEntity>,
    val conversations: Set<String>,
    val groupSenders: Map<String, Set<String>>,
    val authored: Set<String>,
    val blocked: Set<String>,
    val me: String?,
)

/**
 * The live [ConversationTable] for rows of [kinds] (`MessageEntity.KIND_*`), re-read on every write to the
 * messages table and re-subscribed when [blocked] or [me] change.
 *
 * Two `flatMapLatest`s rather than one: the startup `null → id` transition of [me] then re-subscribes the
 * authored query alone, not all four. The `distinctUntilChanged` on each key is load-bearing — both are
 * DataStore-backed in production and re-emit on every preferences write (each read-watermark stamp from an
 * open chat, say), and without it every such write would tear down and re-run every query here. The four
 * Room flows are deliberately *not* deduplicated: a write that leaves the heads as they were can still
 * change an unread count (an older message arriving off a skewed clock, a sweep, a delete), and the chat
 * list recounts on each emission for exactly that reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun MessageRepository.observeConversationTable(
    kinds: Set<Int>,
    blocked: Flow<Set<String>>,
    me: Flow<String?>,
): Flow<ConversationTable> {
    val rows =
        blocked.distinctUntilChanged().flatMapLatest { blockedIds ->
            combine(
                observeNewestPerConversation(kinds, blockedIds),
                observeConversations(blockedIds),
                observeGroupSenders(blockedIds),
            ) { heads, conversations, senders -> TableRows(heads, conversations.toSet(), senders, blockedIds) }
        }
    val authored =
        me.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) {
                flowOf(Authored(null, emptySet()))
            } else {
                observeConversationsIAuthoredIn(id).map { Authored(id, it.toSet()) }
            }
        }
    return combine(rows, authored) { r, a ->
        ConversationTable(r.heads, r.conversations, r.groupSenders, a.conversations, r.blocked, a.me)
    }
}

/** The blocked-keyed half of the table, paired before the final combine so the two keys stay independent. */
private data class TableRows(
    val heads: Map<String, MessageEntity>,
    val conversations: Set<String>,
    val groupSenders: Map<String, Set<String>>,
    val blocked: Set<String>,
)

/** The id-keyed half: our own node id (null until resolved) and the threads we have spoken in. */
private data class Authored(
    val me: String?,
    val conversations: Set<String>,
)
