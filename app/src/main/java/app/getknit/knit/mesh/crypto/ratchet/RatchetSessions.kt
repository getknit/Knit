package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.protocol.EncEnvelope
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
 * **Concurrency contract.** All session mutations serialize on one [mutex], and every critical section
 * takes the Room transaction FIRST via [transact] (transaction-outer, mutex-inner — one global order,
 * no inversion). That order is enforced *here* rather than asked of callers: every locked block below
 * touches the store, Room serves this app through a single connection, and a caller that took the lock
 * without a transaction would deadlock against the decrypt path that takes them the other way round
 * (see [SessionTransactor] for the full account). Because the engine is pure,
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
    // THE ratchet lock — shared with GroupRatchetSessions (seed adoption runs inside a DM commit, so
    // two locks would nest DM→group; one instance makes the order question vanish by construction).
    private val mutex: Mutex = Mutex(),
    // Opens the DB transaction that must enclose the lock. Shared with GroupRatchetSessions for the
    // same reason the mutex is.
    private val transact: SessionTransactor = SessionTransactor.None,
) {
    /**
     * One critical section, in the one global order: transaction OUTER, [mutex] INNER. Every locked
     * block in this class goes through here — including the ones whose caller already opened a
     * transaction, since Room's is reentrant per coroutine and uniformity is what keeps the rule
     * un-forgettable.
     */
    private suspend fun <T> locked(block: suspend () -> T): T = transact.transact { mutex.withLock { block() } }

    /** Per-peer reset heuristic state: the distinct undecryptable frame ids seen (bounded LRU). */
    private val undecryptable = HashMap<String, LinkedHashSet<String>>()

    /** In-memory outbound rate-limit fallback for peers with no session row yet (NO_SESSION resets). */
    private val lastResetSentAt = HashMap<String, Long>()

    /** Inbound session-replacement rate limit (per peer): last accepted replacement/adoption. */
    private val lastReplacementAt = HashMap<String, Long>()

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
        now: Long,
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
            // An explicit reset request gets a far shorter floor than an incidental init. `FLAG_RESET` was
            // minted for this and, until ADR 023, was written on the wire and never read — so a peer that
            // had waited out its own 6 h reset floor could still be refused here for another 60 minutes,
            // silently, and the pair stayed wedged. The sender's floor is the real rate limit and is 6×
            // stricter; a peer ignoring it is buggy or hostile, and since it is a pinned contact the only
            // conversation it can churn is the one it is already party to. The short floor remains so that
            // even then the cost is bounded.
            allowReplacement =
                synchronized(lastReplacementAt) {
                    val floor =
                        if (header.flags and RatchetHeader.FLAG_RESET != 0) {
                            RESET_REPLACEMENT_MIN_INTERVAL_MS
                        } else {
                            REPLACEMENT_MIN_INTERVAL_MS
                        }
                    now - (lastReplacementAt[peerId] ?: 0L) >= floor
                },
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
        return engine.open(contextFor(selfNodeId, peerId, peerIkPub, header, now), header, nonce, ct, aad, now)
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
        locked {
            val header = headerOf(wireHeader)
            val outcome = engine.open(contextFor(selfNodeId, peerId, peerIkPub, header, now), header, nonce, ct, aad, now)
            if (outcome !is RatchetEngine.OpenOutcome.Opened) return@locked false
            store.applyOpen(peerId, outcome.delta, headerSe = header.se, headerN = header.n)
            if (outcome.delta.purgePeerRecvState) {
                // A replacement was adopted: start its rate-limit window and clear the reset heuristic —
                // the session is fresh, old failures are moot.
                synchronized(lastReplacementAt) { lastReplacementAt[peerId] = now }
                synchronized(undecryptable) { undecryptable.remove(peerId) }
            }
            onOpened()
            true
        }

    /**
     * Seals one outbound DM under the peer's session, creating it (X3DH against [peerSpk]) on first
     * use and advancing epochs per the engine's rules. Runs read → seal → persist atomically under the
     * session lock and returns the finished v2 [EncEnvelope]; the caller floods it and saves its own
     * plaintext row afterwards (a crash between this commit and the flood is just a chain hole the
     * receiver's skipped-key path absorbs — nothing received can be lost, unlike the open side).
     *
     * Returns null when no epoch base exists — an established session whose peer has since cleared its
     * prekey and contributed no epoch, or a first send with [peerSpk] null. Callers treat null as
     * "fall back to v1", which the peer can always read.
     */
    suspend fun sealDm(
        peerId: String,
        peerIkPub: ByteArray,
        peerSpk: RatchetEngine.PeerPrekey?,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
    ): EncEnvelope? =
        locked {
            val existing = store.session(peerId)
            val initiation =
                if (existing == null) {
                    peerSpk ?: return@locked null
                    engine.initiate(peerId, dhIdentityPriv(), peerIkPub, peerSpk, now)
                } else {
                    null
                }
            val session = initiation?.session ?: existing ?: return@locked null
            val sealed = engine.seal(session, plaintext, aad, peerSpk?.pub, now) ?: return@locked null
            store.commitSend(sealed.session, initiation?.epoch ?: sealed.newLocalEpoch)
            val h = sealed.header
            EncEnvelope(
                v = EncEnvelope.VERSION_RATCHET,
                nonce = sealed.nonce,
                ct = sealed.ct,
                keys = emptyList(),
                r =
                    RatchetHeader(
                        se = h.se,
                        ek = h.ek,
                        pe = h.pe,
                        n = h.n,
                        init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                        flags = h.flags,
                    ),
            )
        }

    /**
     * Records an undecryptable v2 frame ([RatchetEngine.OpenOutcome.Failed.NO_SESSION] /
     * [RatchetEngine.OpenOutcome.Failed.EPOCH_GONE] / [RatchetEngine.OpenOutcome.Failed.AEAD_FAIL] /
     * [RatchetEngine.OpenOutcome.Failed.DUPLICATE]) from a pinned peer and decides whether a session
     * reset is due: at least [RESET_DISTINCT_FRAMES] **distinct**
     * frame ids (custody re-serves the same frame endlessly — one stuck frame must not trigger anything),
     * and not more often than [RESET_MIN_INTERVAL_MS] per peer (persisted on the session row where one
     * exists, so restarts don't bypass it; the in-memory fallback covers the no-session case).
     *
     * `AEAD_FAIL` is the split-brain case — both sides hold a session and the roots disagree — and unlike
     * the other two it never resolves on its own, so it must be able to trigger a reset like they do.
     * `DUPLICATE` is its mirror image, seen from the other end of a half-adopted replacement: the sender
     * restarted its chain and its indices now collide with our stale rows. The **distinct**-id rule above
     * is what separates that from ordinary replay — a re-served or double-delivered frame repeats a single
     * id and can never reach [RESET_DISTINCT_FRAMES], however many times it arrives.
     */
    suspend fun noteUndecryptable(
        peerId: String,
        frameId: String,
        now: Long,
    ): Boolean {
        val distinct =
            synchronized(undecryptable) {
                val ids = undecryptable.getOrPut(peerId) { LinkedHashSet() }
                ids.add(frameId)
                while (ids.size > RESET_TRACKED_FRAMES) ids.remove(ids.first())
                ids.size
            }
        if (distinct < RESET_DISTINCT_FRAMES) return false
        val persisted = store.session(peerId)?.lastResetSentAt ?: 0L
        val inMemory = synchronized(lastResetSentAt) { lastResetSentAt[peerId] ?: 0L }
        return now - maxOf(persisted, inMemory) >= RESET_MIN_INTERVAL_MS
    }

    /**
     * Seals a session **reset request**: a fresh X3DH initiation replacing any local session (the old
     * root drains via prevRoot; our epoch numbering restarts, and the peer's replacement handling
     * purges its stale rows), carrying [plaintext] (the `ctl` reset marker) with [RatchetHeader.FLAG_RESET].
     * Also stamps the outbound rate limit. Null when the peer has no usable prekey.
     *
     * Purges **our own** receive state too ([RatchetStore.purgePeerRecvState]) — the half this used to leave
     * behind. Abandoning a root era is symmetric: the peer drops its stale rows when it adopts this init, and
     * we must drop ours, or its post-replacement epochs meet a surviving chain index from the dead era.
     */
    suspend fun sealResetDm(
        peerId: String,
        peerIkPub: ByteArray,
        peerSpk: RatchetEngine.PeerPrekey?,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
    ): EncEnvelope? =
        locked {
            peerSpk ?: return@locked null
            val old = store.session(peerId)
            val initiation = engine.initiate(peerId, dhIdentityPriv(), peerIkPub, peerSpk, now)
            val session =
                initiation.session.copy(
                    prevRoot = old?.root,
                    prevRootWeAreInitiator = old?.weAreInitiator ?: false,
                    prevRootExpiresAt = if (old != null) now + RatchetEngine.PREV_ROOT_TTL_MS else 0L,
                    lastResetSentAt = now,
                )
            val sealed = engine.seal(session, plaintext, aad, peerSpk.pub, now) ?: return@locked null
            // Abandon our receive side along with the root. The peer purges its stale rows when it adopts
            // this init; nothing was doing the same for ours, so a recv epoch from the dead era survived and
            // the peer's post-replacement frames — whose epoch numbers may reuse the old ones — were judged
            // against its stale chain index and dropped as DUPLICATE. That is unrecoverable by construction:
            // a duplicate is benign, so it drives no reset, and the pair deadlocks in the one direction.
            store.purgePeerRecvState(peerId)
            store.commitSend(sealed.session, initiation.epoch)
            synchronized(lastResetSentAt) { lastResetSentAt[peerId] = now }
            synchronized(undecryptable) { undecryptable.remove(peerId) }
            val h = sealed.header
            EncEnvelope(
                v = EncEnvelope.VERSION_RATCHET,
                nonce = sealed.nonce,
                ct = sealed.ct,
                keys = emptyList(),
                r =
                    RatchetHeader(
                        se = h.se,
                        ek = h.ek,
                        pe = h.pe,
                        n = h.n,
                        init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                        flags = RatchetHeader.FLAG_RESET,
                    ),
            )
        }

    /** Debug-only read of one peer's session row (no mutation), for the bridge's ratchet diagnostics. */
    suspend fun sessionFor(peerId: String): RatchetEngine.SessionState? = locked { store.session(peerId) }

    /** Retention GC passthrough (wired into the existing sweep loops). */
    suspend fun sweep(now: Long) = locked { store.sweep(now) }

    /**
     * The spool plane's key material for every confirmed session: `pairwiseRoot` exports, never raw
     * session roots. Deliberately exported here rather than letting the plane read [RatchetStore], so
     * session secrets stay behind this facade and its mutex — the plane only ever sees the one-way
     * derivation `docs/SPOOL_PROTOCOL.md` §3.1 names.
     *
     * Unconfirmed sessions are skipped: the two sides may still be resolving a replacement race, and a
     * scope derived from a root that is about to be discarded is churn with no continuity value.
     */
    suspend fun exportedRoots(): List<ExportedRoots> =
        locked {
            store.sessionPeerIds().mapNotNull { peerId ->
                val state = store.session(peerId)?.takeIf { it.confirmed } ?: return@mapNotNull null
                ExportedRoots(
                    peerId = peerId,
                    pairwiseRoot = RatchetCrypto.exportRoot(state.root),
                    prevPairwiseRoot = state.prevRoot?.let { RatchetCrypto.exportRoot(it) },
                    prevRootExpiresAt = state.prevRootExpiresAt,
                )
            }
        }

    /**
     * One peer's exported scope roots: the active one plus, until [prevRootExpiresAt], the retiring
     * session's — the drain window that keeps a replaced session's blobs reachable.
     */
    class ExportedRoots(
        val peerId: String,
        val pairwiseRoot: ByteArray,
        val prevPairwiseRoot: ByteArray?,
        val prevRootExpiresAt: Long,
    )

    companion object {
        /** Distinct undecryptable frames from one peer before a reset request fires. */
        const val RESET_DISTINCT_FRAMES = 3

        /** Bound on the per-peer undecryptable-id LRU. */
        const val RESET_TRACKED_FRAMES = 8

        /** Outbound reset floor per peer (persisted on the session row). */
        const val RESET_MIN_INTERVAL_MS = 6 * 60 * 60_000L

        /** Inbound session-replacement floor per peer (in-memory). */
        const val REPLACEMENT_MIN_INTERVAL_MS = 60 * 60_000L

        /**
         * The same floor for an init carrying [RatchetHeader.FLAG_RESET] — a peer explicitly asking to
         * re-establish, not an incidental init. Short enough that genuine recovery is never refused (the
         * sender's own 6 h floor already rate-limits it), long enough to bound the churn a peer ignoring
         * that floor can cost us.
         */
        const val RESET_REPLACEMENT_MIN_INTERVAL_MS = 60_000L
    }
}
