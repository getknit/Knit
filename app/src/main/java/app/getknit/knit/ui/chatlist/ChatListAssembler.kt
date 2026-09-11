package app.getknit.knit.ui.chatlist

import android.content.Context
import app.getknit.knit.R
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.message.isStatusNotice
import app.getknit.knit.data.message.meshRoomChannel
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.meshNodeLabel
import app.getknit.knit.ui.chat.DeliveryStatus
import app.getknit.knit.ui.chat.messagePreview
import app.getknit.knit.ui.chat.transferPreview

/**
 * Folds one [ChatListViewModel.Snapshot] into the list's rows. Pure over the snapshot — it reads the
 * per-thread summaries, never a thread — and leaves every row's `unreadCount` at zero for the ViewModel to
 * fill from a per-thread count (the one number that needs a query per row, and only for the rows drawn).
 *
 * A class rather than one long transform so each rule has a name and a size detekt can see.
 */
internal class ChatListAssembler(
    private val context: Context,
    private val s: ChatListViewModel.Snapshot,
) {
    private val table = s.table
    private val me = table.me
    private val blocked = table.blocked
    private val directory: PeerDirectory = s.directory
    private val activeGroups = s.inputs.groups.filter { !it.left }

    // Left groups too, to hide stray rows.
    private val groupIds =
        s.inputs.groups
            .map { it.groupId }
            .toSet()
    private val verified =
        directory.peers
            .filter { it.verified }
            .map { it.nodeId }
            .toSet()

    fun build(): ChatListUiState {
        // The Nearby room is always present (even with no messages yet). Groups appear from the groups
        // table (so a freshly created group shows even before its first message); DM threads appear once
        // they have a message — excluding any conversation that is actually a group. Most-recent first.
        val nearby =
            rowFor(
                Conversations.NEARBY,
                title = context.getString(R.string.nearby_title),
                isRoom = true,
                isGroup = false,
                avatarHash = null,
            )
        val bridged = bridgedRow()
        val groupRows = groupRows()
        val dms = dmRows()
        val requestCount = requestCount()
        // The list is never literally empty — the Nearby room always has a row — so a fresh install
        // reads as a working screen with nothing to do on it. Nudge until there is: any Nearby message,
        // a group, a DM, or a pending request. Deleting every thread again brings the hint back, which
        // is the state it is written for.
        val gettingStarted =
            nearby.lastMessageAt == null &&
                bridged?.lastMessageAt == null &&
                groupRows.isEmpty() &&
                dms.isEmpty() &&
                requestCount == 0
        return ChatListUiState(
            conversations =
                (listOf(nearby) + listOfNotNull(bridged) + groupRows + dms)
                    .sortedByDescending { it.lastMessageAt ?: 0L },
            requestCount = requestCount,
            neighborCount = s.mesh.neighborCount,
            transportHealth = s.mesh.health,
            relayPlane = s.mesh.relayPlane,
            loraPlane = s.mesh.loraPlane,
            radioWarning = s.mesh.warning,
            showGettingStarted = gettingStarted,
        )
    }

    /**
     * Whether a thread is a stranger's message request — the SAME shared predicate as the notify gate
     * (Nearby / accepted-set / verified peer / self-authored / a known peer has spoken in the group) so this
     * list and the gate agree. A pending DM/group is dropped from the list and surfaced in the Message
     * Requests inbox instead.
     */
    fun isPending(conversationId: String): Boolean =
        conversationId !in blocked &&
            !Conversations.isAccepted(
                conversationId,
                s.inputs.accepted,
                verified,
                table.authored,
                table.groupSenders[conversationId].orEmpty(),
            )

    /**
     * One row. Its last message is the thread's head: the newest normal or direct-transfer row, which is
     * what "speaks for the row" — its preview line, its time, its place in the list. Status notices never
     * do: a contact renaming themselves is worth a line inside the thread and is not worth reordering
     * someone's chat list, and a notice's senderId is the event's *subject* rather than an author, so
     * treating one as the last message would also mis-attribute the preview. A direct transfer is the
     * exception, and both halves of that reasoning are why: its sender really is the author (whoever
     * offered the file), and an offer or a gigabyte arriving is not a footnote about the thread — it is
     * the thread. It still earns no delivery tick and no unread count: nothing was sent anywhere on the
     * mesh, and the card in the thread is the thing that wants answering.
     */
    private fun rowFor(
        conversationId: String,
        title: String,
        isRoom: Boolean,
        isGroup: Boolean,
        avatarHash: String?,
        discriminator: String? = null,
        isBridged: Boolean = false,
    ): ConversationRow {
        val last = table.heads[conversationId]
        // A draft only speaks for the row while it is the newest thing in the thread. Once a message
        // lands after it — ours or theirs — the conversation has moved on and the preview says so;
        // the draft is still in the composer, waiting where it was typed. Ties go to the message,
        // and a peer's `sentAt` is their clock, which is the same skew every row here already sorts on.
        val draft =
            s.inputs.drafts[conversationId]
                ?.takeIf { it.text.isNotBlank() && it.updatedAt > (last?.sentAt ?: 0L) }
                ?.text
        // The tick, and only for our own sends. "Ours" means we wrote it: a heard Meshtastic post sits in
        // our sender column by convention (the phone whose board heard it writes the row), but we did not
        // write a word of it, and hanging a tick on somebody else's words would be wrong. A notice was
        // never sent anywhere, so it can never grow one either.
        val mineLast = last?.takeIf { it.senderId == me && it.originNode == null && !it.isStatusNotice }
        val transferLine = transferLineFor(last)
        return ConversationRow(
            id = conversationId,
            title = title,
            avatarHash = avatarHash,
            isRoom = isRoom,
            isGroup = isGroup,
            lastPreview = previewLineFor(last, transferLine, isDm = !isRoom && !isGroup),
            previewIsTransfer = transferLine != null,
            lastMessageAt = last?.sentAt,
            draft = draft,
            unreadCount = 0, // filled in by the ViewModel from a per-thread count, once our own id is known
            lastStatus = mineLast?.let { DeliveryStatus.of(it) },
            lastDeliveredVia = mineLast?.receivedPlane ?: DeliveryPlane.Unknown,
            discriminator = discriminator,
            isBridged = isBridged,
        )
    }

    /**
     * The Meshtastic room is this phone's own radio's channel, so it exists whenever a radio is bound —
     * empty until the channel speaks, like Nearby — and stays while history does after the radio goes.
     * Never on a phone with no radio and no history: a standing empty row there would be an offer of
     * something this install cannot have. And never at all once the user has switched the room off: that
     * hides the row **including** its history, which is the whole of what "hidden" means here — the rows
     * stay in the database and come back with the switch.
     */
    private fun bridgedRow(): ConversationRow? {
        val mesh = s.mesh
        val hasHistory = Conversations.MESHTASTIC in table.conversations
        if (!mesh.loraRoom || (mesh.loraPlane == LoraPlane.Off && !hasHistory)) return null
        return rowFor(
            Conversations.MESHTASTIC,
            // The live board's channel, else the newest post's, else the generic label — the same rule
            // the thread header uses, so the list and the screen agree.
            title = meshRoomChannel(mesh.publicChannel, s.inputs.bridgedChannel) ?: context.getString(R.string.meshtastic_title),
            isRoom = true,
            isGroup = false,
            avatarHash = null,
            isBridged = true,
        )
    }

    private fun groupRows(): List<ConversationRow> =
        activeGroups.filter { !isPending(it.groupId) }.map { g ->
            val title =
                groupTitle(
                    storedName = g.name,
                    memberIds = GroupMembersStore.decode(g.members),
                    selfId = me,
                    fallback = context.getString(R.string.group_unnamed),
                ) { id -> directory.label(id).text }
            val row = rowFor(g.groupId, title, isRoom = false, isGroup = true, avatarHash = g.photoHash)
            // An empty group sorts/labels by its creation time so it isn't stranded at the bottom.
            if (row.lastMessageAt == null) row.copy(lastMessageAt = g.createdAt) else row
        }

    private fun dmRows(): List<ConversationRow> =
        table.conversations
            .filter {
                it != Conversations.NEARBY &&
                    it != Conversations.MESHTASTIC &&
                    it !in blocked &&
                    it !in groupIds &&
                    !isPending(it)
            }.map { conversationId ->
                rowFor(
                    conversationId,
                    title = directory.label(conversationId).text,
                    isRoom = false,
                    isGroup = false,
                    avatarHash = directory.byNode[conversationId]?.avatarHash,
                    discriminator = directory.label(conversationId).discriminator,
                )
            }

    /** Count of threads moved to the requests inbox (mirrors exactly what the two filters above drop). */
    private fun requestCount(): Int =
        table.conversations.count {
            it != Conversations.NEARBY && it != Conversations.MESHTASTIC && it !in groupIds && isPending(it)
        } + activeGroups.count { isPending(it.groupId) }

    /**
     * The row's preview line: the transfer's own sentence when [transferLine] resolved one, else the
     * "Sender: body" form.
     */
    private fun previewLineFor(
        last: MessageEntity?,
        transferLine: String?,
        isDm: Boolean,
    ): String? = transferLine ?: last?.let { previewFor(it, isDm) }

    /**
     * The transfer line for [last], or null when it is not a transfer row this build can read. Resolved
     * apart from [previewFor] because it takes no "You: " prefix — it already says who did what ("They
     * declined clip.mp4"), and a prefix would name the wrong person — and because the row needs to know it
     * chose this line, to draw the feature's mark beside it.
     */
    private fun transferLineFor(last: MessageEntity?): String? =
        last?.takeIf { it.kind == MessageEntity.KIND_FILE_TRANSFER }?.let { transferPreview(context, it, s.inputs.transfers) }

    /**
     * "Sender: body" preview, mirroring how ChatViewModel resolves names and labels own messages.
     * In a 1:1 DM the peer's name is already the row title, so an incoming message shows just its body;
     * our own messages still get the "You: …" prefix (it's not the recipient's name and signals who spoke).
     */
    private fun previewFor(
        message: MessageEntity,
        isDm: Boolean,
    ): String {
        val body = messagePreview(context, message)
        // A heard Meshtastic post's author is the speaker, never us — the row sits in our sender column by
        // convention, so without this the preview would read "You: …" over somebody else's words. A speaker
        // whose board a contact's profile claims is named as that contact; a stranger is the NodeDB name the
        // board had for them, else the `!hex` id every Meshtastic client would show.
        message.originNode?.let { node ->
            val contact = message.originPeerId?.let { directory.label(it) }
            val speaker = contact?.text ?: message.originName?.takeIf { it.isNotBlank() } ?: meshNodeLabel(node)
            return context.getString(R.string.chat_list_preview_with_sender, speaker, body)
        }
        val isOwn = message.senderId == me
        if (isDm && !isOwn) return body
        val sender =
            if (isOwn) {
                context.getString(R.string.chat_self_name)
            } else {
                directory.label(message.senderId).text
            }
        return context.getString(R.string.chat_list_preview_with_sender, sender, body)
    }
}
