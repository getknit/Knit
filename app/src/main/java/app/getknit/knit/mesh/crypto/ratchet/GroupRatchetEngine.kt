package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.AesGcm

/**
 * The group sender-key ratchet state machine (crypto scheme v3, docs/GROUP_FORWARD_SECRECY.md). Pure:
 * state comes in as immutable snapshots, changes go out as [SealResult]/[OpenOutcome] deltas the caller
 * persists (atomically with the message row — see `GroupRatchetSessions`); no IO, no Android,
 * randomness only through the injected seed source.
 *
 * Much smaller than the DM [RatchetEngine], deliberately: there are no sessions, no DH, and no races.
 * Each member's chain state is authored solely by that member — the trust, freshness, and healing
 * problems all live in the pairwise v2 DM ratchet that distributes the epoch seeds. What remains here:
 *
 * - **Epochs derive independently** from their seed (`GroupRatchetCrypto.deriveEpoch` binds
 *   groupId + senderId + epoch): any subset of a sender's epochs opens in any order; a wholly-lost
 *   epoch loses only itself (the custody-hole invariant, same as the DM engine's).
 * - **Wipe recovery is mint-stamped, not session-replaced.** A sender that lost state re-mints from
 *   epoch 1 with a fresh [SendChain.mintedAt]; receivers keep rows keyed by `(epoch, mintedAt)`, adopt
 *   the newer mint, and let older-mint rows drain for a bounded window (the DM `prevRoot` pattern) so
 *   both eras' custody re-serves still open. [open] tries chains newest-mint-first; the AEAD decides.
 * - **A receive chain, once derived, never needs the seed again** — receivers discard the seed at
 *   adoption ([adoptSeed] returns only the derived chain), which is the receiver half of delete-as-you-go.
 */
class GroupRatchetEngine(
    private val newSeed: () -> ByteArray = GroupRatchetCrypto::newSeed,
) {
    /** Our own send chain for one group (mirrors a `group_send_chains` row). [seed] is retained for
     *  (re-)distribution while the epoch is current or draining — deleting the row is the sender-side
     *  forward-secrecy guarantee. [export] feeds [GroupRatchetCrypto.exportEpochSeal] (API-only). */
    data class SendChain(
        val groupId: String,
        val epoch: Int,
        val seed: ByteArray,
        val chainKey: ByteArray,
        val count: Int,
        val mintedAt: Long,
        val export: ByteArray,
    )

    /** One inbound per-sender epoch chain (mirrors a `group_recv_chains` row). Keyed by
     *  `(groupId, senderId, epoch, mintedAt)` so a wiped sender's re-mint coexists with the draining
     *  old era instead of overwriting it. */
    data class RecvChain(
        val groupId: String,
        val senderId: String,
        val epoch: Int,
        val mintedAt: Long,
        val chainKey: ByteArray,
        val next: Int,
        val lastUsedAt: Long,
    )

    /** A stored out-of-order message key (mirrors a `group_skipped_keys` row). */
    class SkippedKey(
        val epoch: Int,
        val mintedAt: Long,
        val idx: Int,
        val msgKey: ByteArray,
        val createdAt: Long,
    )

    /** Wire-agnostic mirror of the v3 group header (mapped to/from the CBOR DTO by the caller). */
    class FrameHeader(
        val se: Int,
        val n: Int,
    )

    class SealResult(
        val header: FrameHeader,
        val nonce: ByteArray,
        val ct: ByteArray,
        val chain: SendChain,
    )

    /** Everything [open] needs that lives in storage; the caller resolves rows, the engine does math.
     *  [chains] are the recv chains for the header's `(group, sender, se)` newest-mint-first (at most
     *  the live mint plus a draining older one); [skippedMsgKeys] any stored keys for `(se, n)`. */
    class OpenContext(
        val chains: List<RecvChain>,
        val skippedMsgKeys: List<SkippedKey> = emptyList(),
    )

    sealed interface OpenOutcome {
        class Opened(
            val plaintext: ByteArray,
            val delta: OpenDelta,
        ) : OpenOutcome

        /** Typed failures; the caller maps these to `DropReason`s and the key-request heuristic. */
        enum class Failed : OpenOutcome {
            /** No adopted seed covers this `(sender, epoch)` — the seed DM hasn't arrived (or is lost). */
            NO_KEY,

            /** Chain index already consumed and no skipped key held — a benign re-delivery. */
            DUPLICATE,

            /** Structurally invalid or bound-violating header. */
            BAD_HEADER,

            /** Key material resolved but the AEAD refused — stale/foreign mint era or corrupt frame
             *  (the outer frame signature was already verified, so never third-party tamper). */
            AEAD_FAIL,
        }
    }

    class OpenDelta(
        /** Upsert (advanced chain); null when only a skipped key was consumed. */
        val recvChain: RecvChain?,
        val skippedInserts: List<SkippedKey> = emptyList(),
        /** Chain index whose stored skipped key(s) were consumed — delete every mint's row for it. */
        val consumedSkippedIdx: Int? = null,
    )

    /** Verdict on an inbound seed distribution (from a `CTL_GROUP_KEY` ctl DM). */
    sealed interface AdoptOutcome {
        /** New chain to insert; older-mint rows for the same `(sender, epoch)` drain via the sweep. */
        class Adopt(
            val recv: RecvChain,
        ) : AdoptOutcome

        /** Same `(epoch, mintedAt)` already adopted — a custody re-serve; never rewinds the chain. */
        object AlreadyKnown : AdoptOutcome

        /** An older mint of an epoch we already hold newer state for — ignore. */
        object Stale : AdoptOutcome
    }

    /** Mints our next send epoch for [groupId]: fresh seed, chain at index 0, stamped [now]. */
    fun mint(
        groupId: String,
        selfNodeId: String,
        prevEpoch: Int,
        now: Long,
    ): SendChain {
        val seed = newSeed()
        val epoch = prevEpoch + 1
        val keys = GroupRatchetCrypto.deriveEpoch(seed, groupId, selfNodeId, epoch)
        return SendChain(
            groupId = groupId,
            epoch = epoch,
            seed = seed,
            chainKey = keys.chainKey,
            count = 0,
            mintedAt = now,
            export = keys.export,
        )
    }

    /**
     * Whether the next send must mint a fresh epoch. Count and age mirror the custody bounds (one
     * epoch never exceeds a re-servable backlog; no re-deliverable frame belongs to an epoch older
     * than one retention generation) — the forced cases (departure rekey, wipe) surface as a deleted
     * chain, i.e. null.
     */
    fun needsNewEpoch(
        chain: SendChain?,
        now: Long,
    ): Boolean = chain == null || chain.count >= MAX_EPOCH_MESSAGES || now - chain.mintedAt >= MAX_EPOCH_AGE_MS

    /** Seals one frame under [chain]; null when the chain is exhausted (mint first — [needsNewEpoch]). */
    fun seal(
        chain: SendChain,
        plaintext: ByteArray,
        aad: ByteArray,
    ): SealResult? {
        if (chain.count >= MAX_EPOCH_MESSAGES) return null
        val msgKey = GroupRatchetCrypto.messageKey(chain.chainKey)
        val (iv, ct) = AesGcm.encrypt(msgKey, plaintext, aad)
        return SealResult(
            header = FrameHeader(se = chain.epoch, n = chain.count),
            nonce = iv,
            ct = ct,
            chain = chain.copy(chainKey = GroupRatchetCrypto.nextChainKey(chain.chainKey), count = chain.count + 1),
        )
    }

    /**
     * Decides an inbound seed for `(groupId, senderId, epoch)` against [existing] (the newest-mint row
     * we hold for that epoch, if any). Adoption derives the chain and discards the seed; the comparison
     * is on [mintedAt] alone — last-writer-wins across a wiped sender's re-mint, exact-match idempotent
     * across custody re-serves. The caller enforces the per-sender adoption rate limit and the roster
     * gate before calling.
     */
    fun adoptSeed(
        existing: RecvChain?,
        groupId: String,
        senderId: String,
        epoch: Int,
        seed: ByteArray,
        mintedAt: Long,
        now: Long,
    ): AdoptOutcome {
        if (existing != null) {
            if (existing.mintedAt == mintedAt) return AdoptOutcome.AlreadyKnown
            if (existing.mintedAt > mintedAt) return AdoptOutcome.Stale
        }
        val keys = GroupRatchetCrypto.deriveEpoch(seed, groupId, senderId, epoch)
        return AdoptOutcome.Adopt(
            RecvChain(
                groupId = groupId,
                senderId = senderId,
                epoch = epoch,
                mintedAt = mintedAt,
                chainKey = keys.chainKey,
                next = 0,
                lastUsedAt = now,
            ),
        )
    }

    /**
     * The open ladder: stored skipped key → each candidate chain newest-mint-first (gap-filling and
     * storing keys across any index hole, ≤[MAX_EPOCH_MESSAGES]/epoch), with the AEAD arbitrating
     * which mint era a frame belongs to. All failures are delivery-local (the caller never throws out
     * of the inbound handler).
     */
    fun open(
        ctx: OpenContext,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        if (header.se < 1 || header.n < 0 || header.n >= MAX_EPOCH_MESSAGES) return OpenOutcome.Failed.BAD_HEADER
        openFromSkipped(ctx, header, nonce, ct, aad)?.let { return it }
        return openOnChains(ctx, header, nonce, ct, aad, now)
    }

    /** Walks the candidate chains newest-mint-first; the AEAD arbitrates which era the frame is from. */
    private fun openOnChains(
        ctx: OpenContext,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        var sawConsumed = false
        var sawAeadFail = false
        for (chain in ctx.chains) {
            if (header.n < chain.next) {
                // Already consumed on this chain and no skipped key matched — benign re-delivery
                // (unless an older mint below can still open it).
                sawConsumed = true
                continue
            }
            when (val outcome = openOnChain(chain, header, nonce, ct, aad, now)) {
                is OpenOutcome.Opened -> return outcome
                else -> sawAeadFail = true
            }
        }
        return when {
            ctx.chains.isEmpty() && ctx.skippedMsgKeys.isEmpty() -> OpenOutcome.Failed.NO_KEY
            sawConsumed && !sawAeadFail -> OpenOutcome.Failed.DUPLICATE
            else -> OpenOutcome.Failed.AEAD_FAIL
        }
    }

    /** Tries the stored out-of-order keys for `(se, n)`; a hit consumes them without touching a chain. */
    private fun openFromSkipped(
        ctx: OpenContext,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
    ): OpenOutcome.Opened? {
        for (skipped in ctx.skippedMsgKeys) {
            val plain = runCatching { AesGcm.decrypt(skipped.msgKey, nonce, ct, aad) }.getOrNull()
            if (plain != null) {
                return OpenOutcome.Opened(plain, OpenDelta(recvChain = null, consumedSkippedIdx = header.n))
            }
        }
        return null
    }

    /** Advances [chain] to the header's index, banking skipped keys for the gap; AEAD decides fit. */
    private fun openOnChain(
        chain: RecvChain,
        header: FrameHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): OpenOutcome {
        var chainKey = chain.chainKey
        val skipped = mutableListOf<SkippedKey>()
        for (idx in chain.next until header.n) {
            skipped +=
                SkippedKey(
                    epoch = header.se,
                    mintedAt = chain.mintedAt,
                    idx = idx,
                    msgKey = GroupRatchetCrypto.messageKey(chainKey),
                    createdAt = now,
                )
            chainKey = GroupRatchetCrypto.nextChainKey(chainKey)
        }
        val plain =
            runCatching { AesGcm.decrypt(GroupRatchetCrypto.messageKey(chainKey), nonce, ct, aad) }.getOrNull()
                ?: return OpenOutcome.Failed.AEAD_FAIL
        return OpenOutcome.Opened(
            plain,
            OpenDelta(
                recvChain =
                    chain.copy(
                        chainKey = GroupRatchetCrypto.nextChainKey(chainKey),
                        next = header.n + 1,
                        lastUsedAt = now,
                    ),
                skippedInserts = skipped,
            ),
        )
    }

    companion object {
        /** One epoch never outgrows the per-group/per-sender custody quota (`DEFAULT_MAX_PER_GROUP`/
         *  `DEFAULT_MAX_PER_SENDER`) — a re-served backlog spans at most ~2 epochs of skipped-key work. */
        const val MAX_EPOCH_MESSAGES = 200

        /** The custody TTL: no legitimately re-deliverable frame belongs to an older epoch. */
        const val MAX_EPOCH_AGE_MS = 24 * 60 * 60_000L
    }
}
