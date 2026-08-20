package app.getknit.knit.mesh.crypto.ratchet

/**
 * Persistence seam for the ratchet state (implemented by `data/ratchet/RatchetRepository` over Room;
 * in-memory fakes in tests) — the `ForwardStore`/`ForwardRepository` pattern. Methods are
 * **transaction-agnostic**: `applyOpen`/`commitSend` perform only their own row operations, and the
 * caller (`InboundPipeline`/`MeshManager` via `RatchetSessions`) wraps them in the same
 * `db.withTransaction` as the message row so ratchet advance and plaintext persistence commit
 * atomically (Room transactions are reentrant across suspend calls in the same context).
 */
@Suppress("TooManyFunctions") // A persistence seam mirrors its table set; splitting it would hide the atomicity contract.
interface RatchetStore {
    suspend fun session(peerId: String): RatchetEngine.SessionState?

    /**
     * Every peer we hold session state for. The spool plane's scope table is derived from this
     * ([RatchetSessions.exportedRoots]) — it is the only caller that needs the whole set rather than one
     * peer, since a scope exists per *session*, not per conversation row.
     */
    suspend fun sessionPeerIds(): List<String>

    suspend fun upsertSession(state: RatchetEngine.SessionState)

    suspend fun localEpochPriv(
        peerId: String,
        epoch: Int,
    ): ByteArray?

    suspend fun recvEpoch(
        peerId: String,
        epoch: Int,
    ): RatchetEngine.RecvEpoch?

    suspend fun skippedKey(
        peerId: String,
        epoch: Int,
        idx: Int,
    ): ByteArray?

    /**
     * Persists everything a successful open changed: the session snapshot, the recv-epoch upsert, the
     * skipped-key inserts/consumption, and — on a session replacement — the purge of the peer's stale
     * receive state. [headerSe]/[headerN] locate the consumed skipped key (the delta only flags it).
     */
    suspend fun applyOpen(
        peerId: String,
        delta: RatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    )

    /** Persists a seal's outcome: the advanced session and (on an epoch start) our new epoch keypair. */
    suspend fun commitSend(
        state: RatchetEngine.SessionState,
        newLocalEpoch: RatchetEngine.LocalEpoch?,
    )

    /**
     * Drops our **receive-side** state for [peerId] — recv epochs and skipped keys — leaving the session
     * row and our own epoch privs alone. The mirror of [RatchetEngine.OpenDelta.purgePeerRecvState], for
     * the case where *we* abandon a root era instead of adopting the peer's: after sealing a reset our recv
     * rows describe chains under a root neither side will use again, and the peer's post-replacement epochs
     * can reuse their numbers. A surviving row then decides a fresh frame against a stale chain index and
     * judges it a duplicate (or opens it to the wrong key), which is a deadlock the ratchet cannot escape —
     * a duplicate is benign by definition, so it drives no recovery.
     */
    suspend fun purgePeerRecvState(peerId: String)

    /** Drops the session and ALL per-peer ratchet state (an explicit local reset; not used in normal flow). */
    suspend fun deletePeer(peerId: String)

    /**
     * The (epoch, createdAt) of every local epoch priv held for [peerId], most recently minted first —
     * the debug bridge's ground truth for an `EPOCH_GONE` diagnosis (which of our epochs actually
     * survive, and from which era their `createdAt` says they came). Defaulted so test fakes need not
     * implement it.
     */
    suspend fun debugLocalEpochs(peerId: String): List<Pair<Int, Long>> = emptyList()

    /**
     * Retention GC, riding the existing sweep loops (10-min forward sweep, 15-min heal, startup):
     * skipped keys and recv epochs past 48 h, the skipped-key global cap, retired local epoch privs
     * (superseded + acked + 48 h, keep newest 3, hard cap/TTL) — the PFS window's enforcement point.
     */
    suspend fun sweep(now: Long)
}
