package app.getknit.knit.data.ratchet

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One DM ratchet session per peer (crypto scheme v2 — docs/FORWARD_SECRECY_RATCHET.md). Columns mirror
 * `RatchetEngine.SessionState` one-to-one; the repository maps between them. Key material lives in the
 * SQLCipher-encrypted DB — same at-rest posture as carried ciphertext and attachment keys. Sessions die
 * with a DB wipe by design (the identity + prekeys in `identity.key` survive); the reset path recovers.
 */
@Entity(tableName = "ratchet_sessions")
data class RatchetSessionEntity(
    @PrimaryKey val peerId: String,
    val confirmed: Boolean,
    val weAreInitiator: Boolean,
    val root: ByteArray,
    val prevRoot: ByteArray?,
    val prevRootWeAreInitiator: Boolean,
    val prevRootExpiresAt: Long,
    val establishedAt: Long,
    val initEphPub: ByteArray?,
    val initPkid: Int,
    val peerInitEphPub: ByteArray?,
    val peerBasePub: ByteArray?,
    val peerBaseEpoch: Int,
    val sendEpoch: Int,
    val sendEpochPub: ByteArray?,
    val sendChainKey: ByteArray?,
    val sendCount: Int,
    val sendEpochStartedAt: Long,
    val sendEpochBaseEpoch: Int,
    val sendEpochExport: ByteArray?,
    val highestPeAcked: Int,
    val lastResetSentAt: Long,
    val updatedAt: Long,
)

/**
 * One of OUR epoch keypairs for [peerId]'s session — the peer DHs new epochs against [pub], so [priv]
 * must survive until the epoch is superseded-and-acknowledged. **Deleting these rows is the
 * forward-secrecy guarantee** (see `RatchetRepository.sweep`).
 */
@Entity(
    tableName = "ratchet_local_epochs",
    primaryKeys = ["peerId", "epoch"],
    indices = [Index("createdAt")],
)
data class RatchetLocalEpochEntity(
    val peerId: String,
    val epoch: Int,
    val priv: ByteArray,
    val pub: ByteArray,
    val createdAt: Long,
)

/**
 * One inbound epoch chain from [peerId]: the forward-only [chainKey] positioned at index [next].
 * Root-free once derived — which is what lets a superseded session root expire while its epochs keep
 * decrypting late custody re-serves.
 */
@Entity(
    tableName = "ratchet_recv_epochs",
    primaryKeys = ["peerId", "epoch"],
    indices = [Index("lastUsedAt")],
)
data class RatchetRecvEpochEntity(
    val peerId: String,
    val epoch: Int,
    val chainKey: ByteArray,
    val next: Int,
    val lastUsedAt: Long,
)

/** A stored out-of-order message key (single-use; consumed on decrypt, swept after 48 h). */
@Entity(
    tableName = "ratchet_skipped_keys",
    primaryKeys = ["peerId", "epoch", "idx"],
    indices = [Index("createdAt")],
)
data class RatchetSkippedKeyEntity(
    val peerId: String,
    val epoch: Int,
    val idx: Int,
    val msgKey: ByteArray,
    val createdAt: Long,
)
