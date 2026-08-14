package app.getknit.knit.mesh.crypto.ratchet

/**
 * Persistence seam for the ratchet state (implemented by `data/ratchet/RatchetRepository` over Room;
 * in-memory fakes in tests) — the `ForwardStore`/`ForwardRepository` pattern. Methods are
 * **transaction-agnostic**: `applyOpen`/`commitSend` perform only their own row operations, and the
 * caller (`InboundPipeline`/`MeshManager` via `RatchetSessions`) wraps them in the same
 * `db.withTransaction` as the message row so ratchet advance and plaintext persistence commit
 * atomically (Room transactions are reentrant across suspend calls in the same context).
 */
interface RatchetStore {
    suspend fun session(peerId: String): RatchetEngine.SessionState?

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

    /** Drops the session and ALL per-peer ratchet state (an explicit local reset; not used in normal flow). */
    suspend fun deletePeer(peerId: String)

    /**
     * Retention GC, riding the existing sweep loops (10-min forward sweep, 15-min heal, startup):
     * skipped keys and recv epochs past 48 h, the skipped-key global cap, retired local epoch privs
     * (superseded + acked + 48 h, keep newest 3, hard cap/TTL) — the PFS window's enforcement point.
     */
    suspend fun sweep(now: Long)
}
