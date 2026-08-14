package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session service for the DM epoch ratchet (crypto scheme v2): composes the pure [RatchetEngine]
 * with the persistent [RatchetStore] and the identity material, and owns the concurrency contract.
 * Android-free — identity access is lambda-mediated (the `KeyExchange`/`ForwardSync` style), so the
 * whole service drives under plain-JVM tests.
 *
 * **Concurrency contract.** All session mutations serialize on one [mutex]; every caller that combines
 * a mutation with other DB writes takes the Room transaction FIRST and the facade call inside it
 * (transaction-outer, mutex-inner — one global order, no inversion). Because the engine is pure,
 * decrypt is two-phase: [peekOpen] runs lock-free against a snapshot (its plaintext feeds moderation
 * and row-building, which must not sit under the lock — the text classifier can cold-load for
 * seconds), then [commitOpen] re-runs the engine on FRESH state under the lock and persists the delta
 * atomically with the caller's row write. A state change between the phases (a concurrent send, a
 * duplicate delivery on another link) simply makes the commit re-derive or report false — never a
 * lost update, never a double-spent chain step.
 */
class RatchetSessions(
    private val store: RatchetStore,
    private val dhIdentityPriv: () -> ByteArray,
    private val spkPrivFor: (Int) -> ByteArray?,
    private val engine: RatchetEngine = RatchetEngine(),
) {
    private val mutex = Mutex()

    /** Maps the wire header DTO to the engine's wire-agnostic mirror. */
    private fun headerOf(r: RatchetHeader): RatchetEngine.FrameHeader =
        RatchetEngine.FrameHeader(
            se = r.se,
            ek = r.ek,
            pe = r.pe,
            n = r.n,
            init = r.init?.let { RatchetEngine.InitPayload(eph = it.eph, pkid = it.pkid, at = it.at) },
            flags = r.flags,
        )

    private suspend fun contextFor(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        header: RatchetEngine.FrameHeader,
    ): RatchetEngine.OpenContext =
        RatchetEngine.OpenContext(
            selfNodeId = selfNodeId,
            peerId = peerId,
            session = store.session(peerId),
            recvEpoch = store.recvEpoch(peerId, header.se),
            skippedMsgKey = store.skippedKey(peerId, header.se, header.n),
            ownBasePriv = if (header.pe >= 1) store.localEpochPriv(peerId, header.pe) else null,
            ownIkPriv = dhIdentityPriv(),
            peerIkPub = peerIkPub,
            spkPrivForInit = header.init?.let { spkPrivFor(it.pkid) },
        )

    /**
     * Phase one of a v2 decrypt: opens the frame against a state snapshot WITHOUT persisting anything.
     * The plaintext (on [RatchetEngine.OpenOutcome.Opened]) is safe to hand to moderation/row-building;
     * the delta inside the outcome must be ignored — [commitOpen] re-derives it.
     */
    suspend fun peekOpen(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        wireHeader: RatchetHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): RatchetEngine.OpenOutcome {
        val header = headerOf(wireHeader)
        return engine.open(contextFor(selfNodeId, peerId, peerIkPub, header), header, nonce, ct, aad, now)
    }

    /**
     * Phase two: re-opens on fresh state under the session lock and, on success, persists the ratchet
     * delta and runs [onOpened] (the caller's row write) in the same enclosing Room transaction —
     * callers MUST wrap this call in `db.withTransaction { }` when they persist anything alongside it.
     * Returns false when the frame no longer opens (a concurrent delivery already consumed it, or
     * state moved on) — benign; the caller's exists/isNew gates make the visible outcome idempotent.
     */
    suspend fun commitOpen(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        wireHeader: RatchetHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
        onOpened: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            val header = headerOf(wireHeader)
            val outcome = engine.open(contextFor(selfNodeId, peerId, peerIkPub, header), header, nonce, ct, aad, now)
            if (outcome !is RatchetEngine.OpenOutcome.Opened) return@withLock false
            store.applyOpen(peerId, outcome.delta, headerSe = header.se, headerN = header.n)
            onOpened()
            true
        }

    /** Retention GC passthrough (wired into the existing sweep loops). */
    suspend fun sweep(now: Long) = mutex.withLock { store.sweep(now) }
}
