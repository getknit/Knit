package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.AesGcm

/**
 * The DM epoch-rekey ratchet state machine (crypto scheme v2, docs/FORWARD_SECRECY_RATCHET.md). Pure:
 * state comes in as immutable snapshots, changes go out as [SealResult]/[OpenOutcome] deltas the caller
 * persists (atomically with the message row — see `RatchetSessions`); no IO, no Android, randomness
 * only through the injected keypair source. That keeps every ordering/race scenario a plain-JVM test.
 *
 * Design invariants (the mesh's custody semantics force all three — see the design doc):
 * - **Epochs derive independently** off a static per-session root: any subset of a peer's epochs can be
 *   processed in any order, and a wholly-evicted epoch loses only itself.
 * - **Own send-epoch numbers are monotone for the life of the local state**, across root replacements
 *   (both-initiate races, inbound resets). Only a device wipe restarts numbering — and a wipe also
 *   discards the old epoch privs, so `(peer, se)` can only be reused when the session itself was
 *   replaced, which purges the stale receive state ([OpenDelta.purgePeerRecvState]).
 * - **A receive chain, once derived, never needs the root again** — `chainKey` alone advances it, which
 *   is what lets a superseded root ([SessionState.prevRoot]) drain for a bounded window and then vanish.
 *
 * [SessionState] and [RecvEpoch] are data classes for `copy` ergonomics; their [ByteArray] fields make
 * generated equality reference-based, which nothing here relies on.
 */
class RatchetEngine(
    private val newKeyPair: () -> RatchetCrypto.KeyPair = RatchetCrypto::generateKeyPair,
) {
    /** Immutable snapshot of one peer session (mirrors the `ratchet_sessions` row). */
    data class SessionState(
        val peerId: String,
        val confirmed: Boolean,
        val weAreInitiator: Boolean,
        val root: ByteArray,
        val prevRoot: ByteArray? = null,
        val prevRootWeAreInitiator: Boolean = false,
        val prevRootExpiresAt: Long = 0L,
        val establishedAt: Long,
        val initEphPub: ByteArray? = null,
        val initPkid: Int = 0,
        /** The peer init we last resolved (responded / adopted / archived) — the idempotence anchor. */
        val peerInitEphPub: ByteArray? = null,
        val peerBasePub: ByteArray? = null,
        val peerBaseEpoch: Int = 0,
        val sendEpoch: Int = 0,
        val sendEpochPub: ByteArray? = null,
        val sendChainKey: ByteArray? = null,
        val sendCount: Int = 0,
        val sendEpochStartedAt: Long = 0L,
        val sendEpochBaseEpoch: Int = 0,
        val sendEpochExport: ByteArray? = null,
        val highestPeAcked: Int = 0,
        val lastResetSentAt: Long = 0L,
    )

    /** One of our own epoch keypairs (mirrors a `ratchet_local_epochs` row); peers DH against [pub]. */
    class LocalEpoch(
        val epoch: Int,
        val priv: ByteArray,
        val pub: ByteArray,
        val createdAt: Long,
    )

    /** One inbound epoch chain (mirrors a `ratchet_recv_epochs` row). */
    data class RecvEpoch(
        val epoch: Int,
        val chainKey: ByteArray,
        val next: Int,
        val lastUsedAt: Long,
    )

    /** A stored out-of-order message key (mirrors a `ratchet_skipped_keys` row). */
    class SkippedKey(
        val epoch: Int,
        val idx: Int,
        val msgKey: ByteArray,
        val createdAt: Long,
    )

    /** Wire-agnostic mirror of the v2 ratchet header (mapped to/from the CBOR DTO by the caller). */
    class FrameHeader(
        val se: Int,
        val ek: ByteArray,
        val pe: Int,
        val n: Int,
        val init: InitPayload? = null,
        val flags: Int = 0,
    )

    /** Wire-agnostic mirror of the attached X3DH initiation. */
    class InitPayload(
        val eph: ByteArray,
        val pkid: Int,
        val at: Long,
    )

    /** A peer's published signed prekey, already signature-verified by the caller. */
    class PeerPrekey(
        val id: Int,
        val pub: ByteArray,
    )

    class SealResult(
        val header: FrameHeader,
        val nonce: ByteArray,
        val ct: ByteArray,
        val session: SessionState,
        val newLocalEpoch: LocalEpoch?,
    )

    /**
     * Everything [open] needs that lives in storage; the caller resolves rows, the engine does math.
     * [ownBasePriv] is our local-epoch priv for the header's `pe` (when `pe >= 1`); [spkPrivForInit] is
     * our signed-prekey priv for the init's `pkid` (when an init is attached).
     */
    class OpenContext(
        val selfNodeId: String,
        val peerId: String,
        val session: SessionState?,
        val recvEpoch: RecvEpoch?,
        val skippedMsgKey: ByteArray?,
        val ownBasePriv: ByteArray?,
        val ownIkPriv: ByteArray,
        val peerIkPub: ByteArray,
        val spkPrivForInit: ByteArray?,
        /**
         * Whether a session-replacing init (newer `at`, unknown eph, on a resolved session) may be
         * adopted this call. The caller rate-limits replacements (an attacker can't forge one — the
         * frame is signed — but a buggy peer could churn); false makes the init inert, so the frame
         * is judged under the existing roots only.
         *
         * The caller uses a much shorter floor for an init flagged `FLAG_RESET`: an explicit reset request
         * is the recovery path, and refusing it silently is how two peers that had *both* reset each other
         * stayed unreadable in both directions with every X3DH input present (ADR 023).
         */
        val allowReplacement: Boolean = true,
        /**
         * Whether this frame's init carries the wire's `FLAG_RESET` — the peer explicitly asking to
         * re-establish, as opposed to an incidental init riding ordinary traffic. Supplied by the caller
         * for the same reason [allowReplacement] is: the engine is wire-agnostic and must not own the bit.
         *
         * It exempts the init from the [resolveSession] race-remnant refusal. That guard reads a *stale
         * re-serve* out of a confirmed winner's history, and a reset can never be one: it is minted fresh
         * per request, and once adopted its ephemeral becomes the idempotence anchor that makes every
         * re-serve of it inert.
         */
        val resetRequested: Boolean = false,
    )

    sealed interface OpenOutcome {
        class Opened(
            val plaintext: ByteArray,
            val delta: OpenDelta,
        ) : OpenOutcome

        /** Typed failures; the caller maps these to `DropReason`s and the reset heuristic. */
        enum class Failed : OpenOutcome {
            /** No session and no attached init — the peer assumes shared state we don't have. */
            NO_SESSION,

            /** The header references an own epoch or prekey priv we no longer (or never) hold. */
            EPOCH_GONE,

            /** Chain index already consumed and no skipped key held — a benign re-delivery. */
            DUPLICATE,

            /** Structurally invalid or bound-violating header. */
            BAD_HEADER,

            /** Key material resolved but the AEAD refused — wrong root era, corrupt frame, or tamper. */
            AEAD_FAIL,
        }
    }

    class OpenDelta(
        val session: SessionState,
        /** Upsert; null when only a skipped key was consumed for an epoch whose row is already gone. */
        val recvEpoch: RecvEpoch?,
        val skippedInserts: List<SkippedKey> = emptyList(),
        val consumedSkippedIdx: Int? = null,
        /** True when a replacement init was adopted: delete ALL recv epochs + skipped keys for this peer first. */
        val purgePeerRecvState: Boolean = false,
    )

    class Initiation(
        val session: SessionState,
        val epoch: LocalEpoch,
    )

    /**
     * Starts a session toward [peerId] (X3DH against their identity + signed prekey) and its first send
     * epoch. The X3DH ephemeral's private half is consumed by the root derivation and never retained —
     * only its public half rides the wire ([FrameHeader.init]) until the session confirms.
     */
    fun initiate(
        peerId: String,
        ownIkPriv: ByteArray,
        peerIkPub: ByteArray,
        peerSpk: PeerPrekey,
        now: Long,
    ): Initiation {
        val eph = newKeyPair()
        val root = RatchetCrypto.x3dhInitiate(ownIkPriv, eph.priv, peerIkPub, peerSpk.pub)
        val base =
            SessionState(
                peerId = peerId,
                confirmed = false,
                weAreInitiator = true,
                root = root,
                establishedAt = now,
                initEphPub = eph.pub,
                initPkid = peerSpk.id,
            )
        val started = startSendEpoch(base, basePub = peerSpk.pub, baseEpoch = 0, now = now)
        return Initiation(started.session, started.epoch)
    }

    /**
     * Seals [plaintext] in the session's current send epoch, first advancing to a fresh epoch when an
     * advance rule fires (see [needsNewEpoch]). [peerSpkPub] is the DH base while the peer has
     * contributed no epoch of their own yet (`pe = 0`). Returns null only if a fresh epoch is needed
     * and no base exists — callers gate on prekey presence, so that is an upstream bug, not a wire
     * condition.
     */
    fun seal(
        state: SessionState,
        plaintext: ByteArray,
        aad: ByteArray,
        peerSpkPub: ByteArray?,
        now: Long,
        forceNewEpoch: Boolean = false,
    ): SealResult? {
        var session = state
        var newLocal: LocalEpoch? = null
        if (forceNewEpoch || needsNewEpoch(session, now)) {
            val basePub = session.peerBasePub ?: peerSpkPub ?: return null
            val started = startSendEpoch(session, basePub, session.peerBaseEpoch, now)
            session = started.session
            newLocal = started.epoch
        }
        val chainKey = checkNotNull(session.sendChainKey)
        val msgKey = RatchetCrypto.messageKey(chainKey)
        val (nonce, ct) = AesGcm.encrypt(msgKey, plaintext, aad)
        val header =
            FrameHeader(
                se = session.sendEpoch,
                ek = checkNotNull(session.sendEpochPub),
                pe = session.sendEpochBaseEpoch,
                n = session.sendCount,
                init =
                    if (session.confirmed) {
                        null
                    } else {
                        InitPayload(checkNotNull(session.initEphPub), session.initPkid, session.establishedAt)
                    },
            )
        val advanced =
            session.copy(
                sendChainKey = RatchetCrypto.nextChainKey(chainKey),
                sendCount = session.sendCount + 1,
            )
        return SealResult(header, nonce, ct, advanced, newLocal)
    }

    private fun needsNewEpoch(
        session: SessionState,
        now: Long,
    ): Boolean {
        // No epoch yet, or a root-changing resolution (race adoption, replacement) nulled the chain
        // out — the old epoch's keys live under a root the peer no longer accepts.
        if (session.sendEpoch == 0 || session.sendChainKey == null) return true
        val healing = session.peerBaseEpoch > session.sendEpochBaseEpoch
        val full = session.sendCount >= MAX_EPOCH_MESSAGES
        val aged = now - session.sendEpochStartedAt >= MAX_EPOCH_AGE_MS
        return healing || full || aged
    }

    /**
     * Opens one inbound v2 frame. The ladder: a stored skipped key, then the live chain of a known
     * receive epoch (deriving-and-storing keys across any index gap), then a brand-new epoch derivation
     * — under the active root first and the draining [SessionState.prevRoot] second. An attached init
     * may establish, idempotently re-confirm, race-tiebreak, or replace the session; every mutation
     * rides the returned [OpenDelta], and nothing is committed on failure.
     */
    fun open(
        ctx: OpenContext,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        if (!headerSane(header)) return OpenOutcome.Failed.BAD_HEADER
        val resolved = resolveSession(ctx, header, now) ?: return sessionFailure(ctx, header)
        if (!resolved.purge && ctx.skippedMsgKey != null) {
            return decryptWith(ctx.skippedMsgKey, nonce, ct, aad) { plain ->
                OpenOutcome.Opened(
                    plain,
                    OpenDelta(
                        session = touch(resolved.session, header, ctx, now),
                        recvEpoch = ctx.recvEpoch?.copy(lastUsedAt = now),
                        consumedSkippedIdx = header.n,
                    ),
                )
            }
        }
        val epoch = if (resolved.purge) null else ctx.recvEpoch
        return when {
            epoch == null -> openNewEpoch(ctx, resolved, header, nonce, ct, aad, now)
            header.n < epoch.next -> OpenOutcome.Failed.DUPLICATE
            else -> finishOpen(ctx, resolved, epoch.chainKey, epoch.next, header, nonce, ct, aad, now)
        }
    }

    // ---- internals ----------------------------------------------------------------------------------

    private fun headerSane(header: FrameHeader): Boolean =
        header.se >= 1 && header.n in 0 until MAX_EPOCH_MESSAGES &&
            header.ek.size == RatchetCrypto.KEY_BYTES && (header.pe >= 1 || header.init != null)

    private class ResolvedSession(
        val session: SessionState,
        /** Roots to try for a NEW epoch derivation, in order, paired with the epoch sender's session role. */
        val rootCandidates: List<RootCandidate>,
        val purge: Boolean = false,
    )

    private class RootCandidate(
        val root: ByteArray,
        val senderIsInitiator: Boolean,
    )

    private fun sessionFailure(
        ctx: OpenContext,
        header: FrameHeader,
    ): OpenOutcome.Failed =
        if (header.init != null && ctx.spkPrivForInit == null) {
            OpenOutcome.Failed.EPOCH_GONE
        } else {
            OpenOutcome.Failed.NO_SESSION
        }

    /**
     * Applies the attached init (if any) to produce the session this frame should be read under plus
     * the ordered root candidates for a new-epoch derivation. Null means the frame is unreadable at
     * the session level (no session and no usable init). Suppressions: a decision tree over init
     * cases — early returns are the readable form, and hoisting branches out would scatter the one
     * place session-resolution order is defined.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun resolveSession(
        ctx: OpenContext,
        header: FrameHeader,
        now: Long,
    ): ResolvedSession? {
        val session = ctx.session
        val init = header.init
        if (session == null) {
            if (init == null || ctx.spkPrivForInit == null) return null
            val root = respond(ctx, init)
            return ResolvedSession(
                session =
                    SessionState(
                        peerId = ctx.peerId,
                        confirmed = true,
                        weAreInitiator = false,
                        root = root,
                        establishedAt = init.at,
                        peerInitEphPub = init.eph,
                    ),
                rootCandidates = listOf(RootCandidate(root, senderIsInitiator = true)),
            )
        }
        val candidates = mutableListOf(RootCandidate(session.root, !session.weAreInitiator))
        session.prevRoot?.takeIf { session.prevRootExpiresAt > now }?.let {
            candidates += RootCandidate(it, !session.prevRootWeAreInitiator)
        }
        if (init == null) return ResolvedSession(session, candidates)
        // An init we already resolved (responded to, adopted, or archived), riding a later unconfirmed
        // frame or a custody re-serve: idempotent by its ephemeral key — NOT by timestamp, which would
        // let a re-served race loser's init masquerade as a fresh replacement for its whole custody TTL.
        if (session.peerInitEphPub?.contentEquals(init.eph) == true) return ResolvedSession(session, candidates)
        // Both-initiate race: we hold an unconfirmed session WE started and the peer's own init just
        // arrived. Resolved by nodeId tiebreak, deliberately NOT by comparing `init.at` — concurrent
        // inits can carry the identical timestamp, and both sides must pick the same winner.
        if (!session.confirmed && session.weAreInitiator) return resolveRace(ctx, session, init, now)
        // A genuinely stale init (older than the session we already share) changes nothing.
        if (init.at <= session.establishedAt) return ResolvedSession(session, candidates)
        if (ctx.spkPrivForInit == null || !ctx.allowReplacement) return ResolvedSession(session, candidates)
        // The confirmed-initiator race remnant: we won a both-initiate race WITHOUT ever processing the
        // loser's init (their pre-adoption frames were all lost), so no idempotence anchor was recorded
        // — and their init can re-serve from custody with a *newer* timestamp for a full TTL. An init
        // that loses the nodeId tiebreak in this state is that remnant, never a replacement: adopting
        // it would defect to the losing root while the peer sits on the winning one (both "confirmed",
        // permanently diverged).
        //
        // `FLAG_RESET` is exempt, and must be (ADR 024). The remnant this refuses is a re-served init from
        // an era the peer has already left; a reset is the opposite — freshly minted, deliberate, and the
        // only signal a peer that lost its state can send. Refusing it made the blackout one-directional
        // and unrecoverable from the peer's side: only our OWN 6 h heuristic could clear it, so a pair
        // that reset in the wrong order stayed dark for up to six hours per cycle while every X3DH input
        // was present. Adopting it cannot defect to a losing root, because a reset abandons the losing
        // root on the sender's side too.
        val unanchoredRaceWinner = session.confirmed && session.weAreInitiator && session.peerInitEphPub == null
        if (unanchoredRaceWinner && !ctx.resetRequested && session.peerId > ctx.selfNodeId) {
            return ResolvedSession(session, candidates)
        }
        // Replacement (peer reset / re-init after losing state): their epoch numbering restarts at 1,
        // so our recv rows for the old numbering must go; the old root drains via prevRoot for any
        // still-in-flight old frames.
        val newRoot = respond(ctx, init)
        return ResolvedSession(
            session =
                session.copy(
                    confirmed = true,
                    weAreInitiator = false,
                    root = newRoot,
                    prevRoot = session.root,
                    prevRootWeAreInitiator = session.weAreInitiator,
                    prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                    establishedAt = init.at,
                    peerInitEphPub = init.eph,
                    peerBasePub = null,
                    peerBaseEpoch = 0,
                    sendEpochPub = null,
                    sendChainKey = null,
                    sendEpochExport = null,
                ),
            rootCandidates = listOf(RootCandidate(newRoot, senderIsInitiator = true)),
            purge = true,
        )
    }

    /**
     * Both sides initiated concurrently. Deterministic winner on both ends: the init whose initiator
     * has the lexicographically smaller nodeId. The loser's root drains as [SessionState.prevRoot].
     *
     * The side that adopts the winner's root purges its receive state with it: those rows describe chains
     * under the era being abandoned. Send-epoch numbering continuing monotonically is *not* enough on its
     * own — that holds for an ordinary race, but two peers resetting each other race with inits whose
     * sender restarted its numbering, so the winner's fresh epochs collide with the loser's surviving rows.
     * The side that keeps its own root changes no era and keeps its rows.
     */
    private fun resolveRace(
        ctx: OpenContext,
        session: SessionState,
        init: InitPayload,
        now: Long,
    ): ResolvedSession? {
        if (ctx.spkPrivForInit == null) return null
        val peerRoot = respond(ctx, init)
        return if (session.peerId < ctx.selfNodeId) {
            ResolvedSession(
                session =
                    session.copy(
                        confirmed = true,
                        weAreInitiator = false,
                        root = peerRoot,
                        prevRoot = session.root,
                        prevRootWeAreInitiator = true,
                        prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                        establishedAt = init.at,
                        peerInitEphPub = init.eph,
                        sendEpochPub = null,
                        sendChainKey = null,
                        sendEpochExport = null,
                    ),
                rootCandidates =
                    listOf(
                        RootCandidate(peerRoot, senderIsInitiator = true),
                        RootCandidate(session.root, senderIsInitiator = false),
                    ),
                // We just swapped roots, so every recv row we hold describes a chain under the era we are
                // abandoning — exactly the replacement case, and it must purge for the same reason. The
                // numbering argument below does not save us: it holds for an ordinary race, but a race
                // whose inits are RESET requests is one where the winner restarted its epoch numbering
                // (`sealResetDm`), so its fresh epochs land straight on our stale rows and are judged
                // against a consumed chain index — a DUPLICATE, which triggers nothing and never heals.
                purge = true,
            )
        } else {
            ResolvedSession(
                session =
                    session.copy(
                        prevRoot = peerRoot,
                        prevRootWeAreInitiator = false,
                        prevRootExpiresAt = now + PREV_ROOT_TTL_MS,
                        peerInitEphPub = init.eph,
                    ),
                rootCandidates =
                    listOf(
                        RootCandidate(session.root, senderIsInitiator = false),
                        RootCandidate(peerRoot, senderIsInitiator = true),
                    ),
            )
        }
    }

    private fun respond(
        ctx: OpenContext,
        init: InitPayload,
    ): ByteArray =
        RatchetCrypto.x3dhRespond(
            ikPriv = ctx.ownIkPriv,
            spkPriv = checkNotNull(ctx.spkPrivForInit),
            peerIkPub = ctx.peerIkPub,
            peerEkPub = init.eph,
        )

    private fun openNewEpoch(
        ctx: OpenContext,
        resolved: ResolvedSession,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        val basePriv = if (header.pe == 0) ctx.spkPrivForInit else ctx.ownBasePriv
        basePriv ?: return OpenOutcome.Failed.EPOCH_GONE
        val shared =
            runCatching { RatchetCrypto.dh(basePriv, header.ek) }.getOrNull()
                ?: return OpenOutcome.Failed.BAD_HEADER
        for (candidate in resolved.rootCandidates) {
            val keys =
                RatchetCrypto.deriveEpoch(
                    sessionRoot = candidate.root,
                    dhShared = shared,
                    senderIsInitiator = candidate.senderIsInitiator,
                    senderEpoch = header.se,
                    baseEpoch = header.pe,
                )
            val outcome = finishOpen(ctx, resolved, keys.chainKey, 0, header, nonce, ct, aad, now)
            if (outcome !is OpenOutcome.Failed) return outcome
        }
        return OpenOutcome.Failed.AEAD_FAIL
    }

    @Suppress("LongParameterList") // internal plumbing between the two open paths; a param object would just rename it
    private fun finishOpen(
        ctx: OpenContext,
        resolved: ResolvedSession,
        chainKeyAtNext: ByteArray,
        next: Int,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        var chainKey = chainKeyAtNext
        val skipped = mutableListOf<SkippedKey>()
        for (idx in next until header.n) {
            skipped += SkippedKey(epoch = header.se, idx = idx, msgKey = RatchetCrypto.messageKey(chainKey), createdAt = now)
            chainKey = RatchetCrypto.nextChainKey(chainKey)
        }
        val msgKey = RatchetCrypto.messageKey(chainKey)
        val nextChain = RatchetCrypto.nextChainKey(chainKey)
        return decryptWith(msgKey, nonce, ct, aad) { plain ->
            OpenOutcome.Opened(
                plain,
                OpenDelta(
                    session = touch(resolved.session, header, ctx, now),
                    recvEpoch = RecvEpoch(epoch = header.se, chainKey = nextChain, next = header.n + 1, lastUsedAt = now),
                    skippedInserts = skipped,
                    purgePeerRecvState = resolved.purge,
                ),
            )
        }
    }

    /** Post-success bookkeeping: adopt the peer's newest epoch as our next DH base, track pe acks, confirm. */
    private fun touch(
        session: SessionState,
        header: FrameHeader,
        ctx: OpenContext,
        now: Long,
    ): SessionState {
        var out = session
        if (header.se > out.peerBaseEpoch) {
            out = out.copy(peerBasePub = header.ek, peerBaseEpoch = header.se)
        }
        if (header.pe > out.highestPeAcked) out = out.copy(highestPeAcked = header.pe)
        // The peer sealed against one of OUR epochs — they hold our contribution, so the session is
        // live both ways and the X3DH ephemeral pub no longer needs to ride the wire.
        val peerHoldsOurEpoch = header.pe >= 1 && ctx.ownBasePriv != null
        if (!out.confirmed && out.weAreInitiator && peerHoldsOurEpoch) {
            out = out.copy(confirmed = true, initEphPub = null)
        }
        if (out.prevRoot != null && out.prevRootExpiresAt <= now) {
            out = out.copy(prevRoot = null, prevRootExpiresAt = 0L)
        }
        return out
    }

    private inline fun decryptWith(
        key: ByteArray,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        onSuccess: (ByteArray) -> OpenOutcome,
    ): OpenOutcome {
        val plain = runCatching { AesGcm.decrypt(key, nonce, ct, aad) }.getOrNull()
        return if (plain == null) OpenOutcome.Failed.AEAD_FAIL else onSuccess(plain)
    }

    private class Started(
        val session: SessionState,
        val epoch: LocalEpoch,
    )

    private fun startSendEpoch(
        session: SessionState,
        basePub: ByteArray,
        baseEpoch: Int,
        now: Long,
    ): Started {
        val pair = newKeyPair()
        val se = session.sendEpoch + 1
        val keys =
            RatchetCrypto.deriveEpoch(
                sessionRoot = session.root,
                dhShared = RatchetCrypto.dh(pair.priv, basePub),
                senderIsInitiator = session.weAreInitiator,
                senderEpoch = se,
                baseEpoch = baseEpoch,
            )
        return Started(
            session =
                session.copy(
                    sendEpoch = se,
                    sendEpochPub = pair.pub,
                    sendChainKey = keys.chainKey,
                    sendCount = 0,
                    sendEpochStartedAt = now,
                    sendEpochBaseEpoch = baseEpoch,
                    sendEpochExport = keys.export,
                ),
            epoch = LocalEpoch(epoch = se, priv = pair.priv, pub = pair.pub, createdAt = now),
        )
    }

    companion object {
        /** Epoch length cap — matches the mesh's 200-per-sender custody quota (design doc §advance rules). */
        const val MAX_EPOCH_MESSAGES = 200

        /** Epoch age cap — matches the 24h custody TTL. */
        const val MAX_EPOCH_AGE_MS = 24 * 60 * 60_000L

        /** How long a superseded root keeps decrypting in-flight epochs (2x the custody TTL). */
        const val PREV_ROOT_TTL_MS = 48 * 60 * 60_000L
    }
}
