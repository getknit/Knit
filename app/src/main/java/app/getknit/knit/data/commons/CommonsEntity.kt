package app.getknit.knit.data.commons

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A commons this device has joined — a spool's shared room (docs/SPOOL_PROTOCOL.md §7.4), the private
 * instance's group chat with every peer on that relay. One row per room, keyed by the conversation id its
 * posts land in (`c-` + the scope id in hex, `Conversations.commonsIdFor`).
 *
 * [secret] is the 32-byte invite the operator handed out; the scope id the spool knows is its hash and the
 * seal keys are stretched from it, so this row is the whole key to the room and lives in the
 * SQLCipher-encrypted database like the ratchet tables and the group roots. [spoolUrl] is the one relay
 * that runs the room — a commons is subscribed there and nowhere else. [name] is what the relay
 * advertised in its `hello` at join time, shown as the thread title; null when the operator set none.
 * Leaving deletes the row, its outbox, its members and its messages together ([CommonsRepository.leave]).
 */
@Entity(tableName = "commons")
data class CommonsEntity(
    @PrimaryKey val conversationId: String,
    val spoolUrl: String,
    val secret: ByteArray,
    val name: String? = null,
    val joinedAt: Long,
)

/**
 * One of our own posts in a commons, as the exact bytes it was signed as. The Internet plane's seal is
 * deterministic over `sig ‖ signed`, so re-sealing on every heal round needs these bytes verbatim — and a
 * post is deliberately never put in mesh custody (a room only some nodes are in can never fold into a
 * digest every node must compute alike), so this table is its only home. [sentAt] mirrors the frame's own
 * clock so the sweep can expire rows without decoding them; a row outlives the spool's retention by
 * nothing — once the spool has tombstoned it, a re-push is refused and the next sweep drops it.
 */
@Entity(
    tableName = "commons_outbox",
    indices = [Index(value = ["conversationId"])],
)
data class CommonsOutboxEntity(
    @PrimaryKey val frameId: String,
    val conversationId: String,
    val sig: ByteArray,
    val signed: ByteArray,
    val sentAt: Long,
)

/**
 * A node whose frame this device pulled from a commons — a profile or a post. A commons has no pinned
 * roster (whoever holds the invite is in), so this is the roster it has instead: what makes a member a
 * contact, what the DM-bootstrap sweep walks, and what a member list would read. [seenAt] is our clock at
 * the latest sighting; rows are never expired on their own and go with the room on leave.
 */
@Entity(
    tableName = "commons_members",
    primaryKeys = ["conversationId", "nodeId"],
)
data class CommonsMemberEntity(
    val conversationId: String,
    val nodeId: String,
    val seenAt: Long,
)
