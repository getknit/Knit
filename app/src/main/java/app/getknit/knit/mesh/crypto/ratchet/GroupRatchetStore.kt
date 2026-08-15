package app.getknit.knit.mesh.crypto.ratchet

/**
 * One member's seed-distribution state for a group — the **outbox** row (docs/GROUP_FORWARD_SECRECY.md
 * §3). Custody of the seed ctl DM is only an accelerator; this row is the source of truth for "does
 * [memberId] hold our current epoch": [sentEpoch]/[sentAt] record the newest distribution attempt,
 * [ackedEpoch]/[ackedAt] the newest `CTL_GROUP_KEY_ACK` — re-send triggers fire while
 * `ackedEpoch < sentEpoch` (or no row).
 */
class GroupKeySendState(
    val groupId: String,
    val memberId: String,
    val sentEpoch: Int,
    val sentAt: Long,
    val ackedEpoch: Int,
    val ackedAt: Long,
)

/**
 * Persistence seam for the group sender-key ratchet state (implemented by
 * `data/ratchet/GroupRatchetRepository` over Room; in-memory fakes in tests) — the [RatchetStore]
 * pattern, same **transaction-agnostic** contract: methods perform only their own row operations, and
 * the caller (via `GroupRatchetSessions`) wraps `applyOpen`/`commitSend`/`insertRecvChain` in the same
 * `db.withTransaction` as the surrounding mutation so state advance and plaintext persistence commit
 * atomically.
 */
@Suppress("TooManyFunctions") // one seam, four row families (send/recv/skipped/outbox); splitting would obscure
interface GroupRatchetStore {
    /** Our newest send chain for [groupId] (the sealing epoch), or null when none was ever minted. */
    suspend fun sendChain(groupId: String): GroupRatchetEngine.SendChain?

    /** Our retained send chains newest-epoch-first (the current one plus the ≤48 h draining previous). */
    suspend fun sendChains(groupId: String): List<GroupRatchetEngine.SendChain>

    /** Persists a mint or a seal advance (PK `(groupId, epoch)` — a mint inserts, an advance updates). */
    suspend fun commitSend(chain: GroupRatchetEngine.SendChain)

    /** Drops ALL our send chains for [groupId] — the leave-rekey force (next send mints fresh). */
    suspend fun deleteSendChains(groupId: String)

    /** Recv chains for the header's coordinates, newest-mint-first (live mint + draining older era). */
    suspend fun recvChains(
        groupId: String,
        senderId: String,
        epoch: Int,
    ): List<GroupRatchetEngine.RecvChain>

    /** Stored out-of-order keys for exactly `(groupId, senderId, epoch, idx)`, any mint era. */
    suspend fun skippedKeys(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    ): List<GroupRatchetEngine.SkippedKey>

    /** Inserts a freshly adopted recv chain (older mints of the same epoch stay, draining via sweep). */
    suspend fun insertRecvChain(chain: GroupRatchetEngine.RecvChain)

    /**
     * Persists everything a successful open changed: the recv-chain upsert, the skipped-key inserts,
     * and the consumed skipped key(s) — every mint era's row for `(headerSe, headerN)`, since the
     * delta only flags the index (deleting a sibling era's same-index key at worst costs one late
     * frame a benign AEAD_FAIL → key-request).
     */
    suspend fun applyOpen(
        groupId: String,
        senderId: String,
        delta: GroupRatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    )

    /** Drops ALL group-ratchet state for [groupId] (local leave/delete): chains, keys, outbox. */
    suspend fun purgeGroup(groupId: String)

    /** The outbox row for one member, or null when never distributed. */
    suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendState?

    /** All outbox rows for [groupId]. */
    suspend fun keySends(groupId: String): List<GroupKeySendState>

    /** Records a distribution attempt of [epoch] to [memberId] (upsert; keeps any recorded ack). */
    suspend fun markKeySent(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    )

    /** Records the member's adoption ack for [epoch]; a stale ack (older epoch) still records monotonically. */
    suspend fun markKeyAcked(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    )

    /** Drops one member's outbox row (their departure — they stop being a distribution target). */
    suspend fun deleteKeySend(
        groupId: String,
        memberId: String,
    )

    /**
     * Retention GC, riding the existing sweep loops: recv chains + skipped keys past 48 h of last
     * use, the group skipped-key global cap, and superseded send chains 48 h past their successor's
     * mint — the sender-side forward-secrecy enforcement point.
     */
    suspend fun sweep(now: Long)
}
