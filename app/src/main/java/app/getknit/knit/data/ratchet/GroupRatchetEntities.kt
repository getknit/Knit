package app.getknit.knit.data.ratchet

import androidx.room.Entity
import androidx.room.Index

/**
 * One of OUR send epochs for a group (crypto scheme v3 — docs/GROUP_FORWARD_SECRECY.md). The [seed]
 * is retained while the epoch is current (re-distribution to members who lost it) or draining
 * (≤48 h past its successor's mint, for key-request recovery of custody re-serves) — **deleting these
 * rows is the sender-side forward-secrecy guarantee** (see `GroupRatchetRepository.sweep`). Key
 * material lives in the SQLCipher-encrypted DB, same at-rest posture as the DM ratchet's; group chains
 * die with a DB wipe by design (the re-mint + key-request path recovers).
 */
@Entity(
    tableName = "group_send_chains",
    primaryKeys = ["groupId", "epoch"],
    indices = [Index("mintedAt")],
)
data class GroupSendChainEntity(
    val groupId: String,
    val epoch: Int,
    val seed: ByteArray,
    val chainKey: ByteArray,
    val count: Int,
    val mintedAt: Long,
    val export: ByteArray,
    val updatedAt: Long,
)

/**
 * One inbound per-sender epoch chain: the forward-only [chainKey] positioned at index [next].
 * Keyed by [mintedAt] too — a wiped sender's re-mint of the same epoch number inserts a NEW row and
 * the old era drains via the sweep (the DM `prevRoot` pattern), so both eras' custody re-serves keep
 * decrypting. The seed itself is never stored on the receive side (discarded at adoption).
 */
@Entity(
    tableName = "group_recv_chains",
    primaryKeys = ["groupId", "senderId", "epoch", "mintedAt"],
    indices = [Index("lastUsedAt")],
)
data class GroupRecvChainEntity(
    val groupId: String,
    val senderId: String,
    val epoch: Int,
    val mintedAt: Long,
    val chainKey: ByteArray,
    val next: Int,
    val lastUsedAt: Long,
)

/** A stored out-of-order group message key (single-use; consumed on decrypt, swept after 48 h). */
@Entity(
    tableName = "group_skipped_keys",
    primaryKeys = ["groupId", "senderId", "epoch", "mintedAt", "idx"],
    indices = [Index("createdAt")],
)
data class GroupSkippedKeyEntity(
    val groupId: String,
    val senderId: String,
    val epoch: Int,
    val mintedAt: Long,
    val idx: Int,
    val msgKey: ByteArray,
    val createdAt: Long,
)

/**
 * The seed-distribution **outbox**: per (group, member), the newest epoch we attempted to distribute
 * ([sentEpoch]/[sentAt]) and the newest the member acknowledged adopting ([ackedEpoch]/[ackedAt],
 * from `CTL_GROUP_KEY_ACK`). Source of truth for re-send triggers — custody of the seed ctl DM is
 * only an accelerator. Rows die with the member's departure or the group's leave/delete.
 */
@Entity(
    tableName = "group_key_sends",
    primaryKeys = ["groupId", "memberId"],
)
data class GroupKeySendEntity(
    val groupId: String,
    val memberId: String,
    val sentEpoch: Int,
    val sentAt: Long,
    val ackedEpoch: Int,
    val ackedAt: Long,
)
