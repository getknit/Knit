package app.getknit.knit.data

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore
import app.getknit.knit.mesh.spool.GroupRootStore
import kotlinx.coroutines.flow.Flow

/** Single source of truth for group chats (and, transactionally, their group-ratchet state hooks). */
class GroupRepository(
    private val dao: GroupDao,
    private val messages: MessageRepository,
    private val db: KnitDatabase,
    private val groupRatchet: GroupRatchetStore,
    // The spool plane's group root (docs/SPOOL_PROTOCOL.md §3.2) shares this class's two lifecycle hooks:
    // a departure obliges a re-mint, and leaving/deleting drops the root with the rest of the group's key
    // state. Nullable so rigs that don't exercise the plane construct unchanged.
    private val groupRoots: GroupRootStore? = null,
) {
    fun observeGroups(): Flow<List<GroupEntity>> = dao.observeAll()

    fun observeGroup(groupId: String): Flow<GroupEntity?> = dao.observeById(groupId)

    suspend fun find(groupId: String): GroupEntity? = dao.findById(groupId)

    suspend fun upsert(group: GroupEntity) = dao.upsert(group)

    /** Every group we still hold (leave-tombstoned rows excluded). */
    suspend fun active(): List<GroupEntity> = dao.allActive()

    /**
     * The non-left groups whose effective roster contains [memberId] (the roster is a JSON column, so
     * this filters in memory — bounded by the user's group count). Feeds the seed re-distribution
     * triggers (docs/GROUP_FORWARD_SECRECY.md §3).
     */
    suspend fun groupsWith(memberId: String): List<GroupEntity> = active().filter { memberId in GroupMembersStore.decode(it.members) }

    /**
     * Records that [leaverId] left [groupId] (from their own signed `groupleave` frame): drops them from
     * the roster, tombstones them in [GroupEntity.departed] so a straggler's stale full roster can't
     * re-add them, and inserts a "member left" status notice stamped [leftAt] (the frame's sentAt, for
     * stable cross-device ordering). The whole read-modify-write plus the message insert run in one
     * transaction so the count and the notice can't tear apart and so a concurrent rename can't clobber
     * the tombstone. Returns true only when [leaverId] was actually a current member — a no-op (already
     * gone, never a member, or a group we've left) returns false, which both dedups a re-flooded leave
     * and tells the caller not to surface anything. The status row's id is deterministic so a replay
     * upserts the same row rather than duplicating it.
     */
    suspend fun recordDeparture(
        groupId: String,
        leaverId: String,
        leftAt: Long,
    ): Boolean =
        db.withWriteTransaction {
            val group = dao.findById(groupId) ?: return@withWriteTransaction false
            if (group.left) return@withWriteTransaction false
            val members = GroupMembersStore.decode(group.members)
            if (leaverId !in members) return@withWriteTransaction false
            val departed = GroupMembersStore.decode(group.departed)
            dao.upsert(
                group.copy(
                    members = GroupMembersStore.encode(members - leaverId),
                    departed = GroupMembersStore.encode((departed + leaverId).distinct()),
                ),
            )
            // Leave-rekey (docs/GROUP_FORWARD_SECRECY.md #6.1), atomic with the roster shrink: drop our
            // send chains so the next send mints a fresh epoch distributed to the REMAINING members only
            // (the leaver reads nothing sealed after this commits), and drop the leaver's outbox row.
            // Their recv chains drain via the 48h sweep — their pre-leave frames may still re-serve.
            groupRatchet.deleteSendChains(groupId)
            groupRatchet.deleteKeySend(groupId, leaverId)
            // The spool plane's rotation hook (docs/SPOOL_PROTOCOL.md §3.2), atomic with the same roster
            // shrink: record that a re-mint is OWED. The mint itself happens on the heal pass, not here —
            // splitting the obligation from the act is what makes rotation survive a crash between them,
            // and it is what lets the mint grace cover a deterministic re-minter who never comes back.
            groupRoots?.markRemintDue(groupId, leftAt)
            messages.save(
                MessageEntity(
                    id = "leave:$groupId:$leaverId",
                    senderId = leaverId,
                    conversationId = groupId,
                    body = "",
                    sentAt = leftAt,
                    received = true,
                    kind = MessageEntity.KIND_MEMBER_LEFT,
                ),
            )
            true
        }

    /**
     * Leaves [groupId]: tombstones the row (so inbound frames are dropped and never resurrect it) and
     * deletes the thread's messages so it vanishes from the chat list. The local user stops receiving;
     * other members still treat them as a roster entry (membership is reconstructed per-device from
     * frames), but their frames are now ignored here.
     *
     * The tombstone + message purge run in one transaction so they can't tear apart, and so a
     * concurrent inbound group frame — whose reconcile is likewise transactional — can't observe the
     * row as still-present between the two writes and resurrect it.
     */
    suspend fun leave(groupId: String) {
        db.withWriteTransaction {
            dao.markLeft(groupId)
            messages.deleteByConversation(groupId)
            // All group ratchet state dies with our membership (chains, skipped keys, outbox) — and with it
            // the group's spool root, so the scope stops being derived the moment we leave.
            groupRatchet.purgeGroup(groupId)
            groupRoots?.purge(groupId)
        }
    }

    /**
     * Deletes [groupId] locally without leaving: hard-deletes the row and clears its messages, so the
     * chat disappears now but the next inbound group frame re-creates it via MeshManager.reconcileGroup
     * (contrast [leave], which tombstones to block re-add). The row delete + message purge run in one
     * transaction so they can't tear apart.
     */
    suspend fun delete(groupId: String) {
        db.withWriteTransaction {
            dao.deleteById(groupId)
            messages.deleteByConversation(groupId)
            // A re-created group (via reconcileGroup) starts with clean ratchet state — and a clean root, so
            // it re-adopts the current one from the first gossiping ctl DM rather than reviving a stale scope.
            groupRatchet.purgeGroup(groupId)
            groupRoots?.purge(groupId)
        }
    }
}
