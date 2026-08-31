package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.GroupSeed
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session service for the group sender-key ratchet (crypto scheme v2's group form,
 * docs/GROUP_FORWARD_SECRECY.md): composes the pure [GroupRatchetEngine] with the persistent
 * [GroupRatchetStore]. Android-free, like [RatchetSessions], whose two-phase peek/commit contract it
 * copies verbatim.
 *
 * **Concurrency contract — the [mutex] is the ONE ratchet lock, shared with [RatchetSessions].**
 * Seed adoption runs inside a v2 ctl DM's `commitOpen` (`onOpened` — already under the DM lock), so a
 * second lock here would create a DM→group nesting; sharing the instance makes that nesting a no-risk
 * reentrancy question answered by construction: group entry points that take the lock
 * ([commitOpen], [sealGroup], [sweep]) are never called from inside a DM commit, and the ones that
 * are ([adoptSeeds], [onKeyAck]) deliberately take no lock — the DM lock already serializes them.
 *
 * The global order — Room transaction OUTER, the shared mutex INNER — is enforced by [transact] on
 * every locked block rather than asked of callers; see [SessionTransactor] for the deadlock that
 * caller-side convention let through.
 */
class GroupRatchetSessions(
    private val store: GroupRatchetStore,
    private val engine: GroupRatchetEngine = GroupRatchetEngine(),
    private val mutex: Mutex = Mutex(),
    // Shared with RatchetSessions for the same reason the mutex is: one order, enforced in one place.
    private val transact: SessionTransactor = SessionTransactor.None,
) {
    /** One critical section, in the one global order: transaction OUTER, [mutex] INNER. */
    private suspend fun <T> locked(block: suspend () -> T): T = transact.transact { mutex.withLock { block() } }

    /** Adoption timestamps per (groupId, senderId) — the epoch-adoption rate limit's memory. */
    private val adoptionTimes = HashMap<Pair<String, String>, ArrayDeque<Long>>()

    /** Key-request heuristic state per (groupId, senderId): distinct undecryptable frame ids (bounded LRU). */
    private val undecryptable = HashMap<Pair<String, String>, LinkedHashSet<String>>()

    /** Outbound key-request floor per (groupId, senderId). */
    private val lastKeyRequestAt = HashMap<Pair<String, String>, Long>()

    private fun headerOf(g: GroupRatchetHeader): GroupRatchetEngine.FrameHeader = GroupRatchetEngine.FrameHeader(se = g.se, n = g.n)

    private suspend fun contextFor(
        groupId: String,
        senderId: String,
        header: GroupRatchetEngine.FrameHeader,
    ): GroupRatchetEngine.OpenContext =
        GroupRatchetEngine.OpenContext(
            chains = store.recvChains(groupId, senderId, header.se),
            skippedMsgKeys = store.skippedKeys(groupId, senderId, header.se, header.n),
        )

    /**
     * Phase one of a group decrypt: opens the frame against a state snapshot WITHOUT persisting anything.
     * The plaintext (on [GroupRatchetEngine.OpenOutcome.Opened]) is safe to hand to moderation/row-
     * building; the delta inside the outcome must be ignored — [commitOpen] re-derives it.
     */
    suspend fun peekOpen(
        groupId: String,
        senderId: String,
        wireHeader: GroupRatchetHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): GroupRatchetEngine.OpenOutcome {
        val header = headerOf(wireHeader)
        return engine.open(contextFor(groupId, senderId, header), header, nonce, ct, aad, now)
    }

    /**
     * Phase two: re-opens on fresh state under the shared lock and, on success, persists the chain
     * delta and runs [onOpened] (the caller's row write) in the same enclosing Room transaction —
     * callers MUST wrap this call in `db.withWriteTransaction { }` when they persist anything alongside it.
     * Returns false when the frame no longer opens (a concurrent delivery consumed it) — benign; the
     * caller's exists/isNew gates make the visible outcome idempotent.
     */
    suspend fun commitOpen(
        groupId: String,
        senderId: String,
        wireHeader: GroupRatchetHeader,
        nonce: ByteArray,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
        onOpened: suspend () -> Unit,
    ): Boolean =
        locked {
            val header = headerOf(wireHeader)
            val outcome = engine.open(contextFor(groupId, senderId, header), header, nonce, ct, aad, now)
            if (outcome !is GroupRatchetEngine.OpenOutcome.Opened) return@locked false
            store.applyOpen(groupId, senderId, outcome.delta, headerSe = header.se, headerN = header.n)
            onOpened()
            true
        }

    /** What one `CTL_GROUP_KEY` distribution amounted to: the highest epoch worth acknowledging
     *  (null ⇒ nothing to ack) and how many chains were freshly derived (0 for a pure re-serve). */
    class AdoptResult(
        val ackEpoch: Int?,
        val freshChains: Int,
    )

    /**
     * Adopts the seeds of one inbound `CTL_GROUP_KEY` distribution. Idempotent per (epoch, mintedAt):
     * a custody re-serve re-acks (the sender's outbox converges even if the first ack was lost)
     * without touching chain position. New adoptions are rate-limited to [MAX_ADOPTIONS_PER_DAY] per
     * (group, sender) — the skipped-key-pump bound; legitimate advances (count, age, leave, wipe) fit
     * under it.
     *
     * **Lock-free by design**: runs inside the v2 DM `commitOpen`'s `onOpened`, i.e. already under the
     * shared ratchet lock and the caller's transaction. The membership/left gating happens in the
     * pipeline, which holds the group rows.
     */
    suspend fun adoptSeeds(
        groupId: String,
        senderId: String,
        seeds: List<GroupSeed>,
        now: Long,
    ): AdoptResult {
        var fresh = 0
        val ackEpoch =
            seeds
                .mapNotNull { seed ->
                    val newest = store.recvChains(groupId, senderId, seed.epoch).firstOrNull()
                    when (val outcome = engine.adoptSeed(newest, groupId, senderId, seed.epoch, seed.seed, seed.mintedAt, now)) {
                        is GroupRatchetEngine.AdoptOutcome.Adopt -> {
                            if (!recordAdoption(groupId, senderId, now)) return@mapNotNull null
                            store.insertRecvChain(outcome.recv)
                            fresh++
                            seed.epoch
                        }

                        GroupRatchetEngine.AdoptOutcome.AlreadyKnown -> {
                            seed.epoch
                        }

                        GroupRatchetEngine.AdoptOutcome.Stale -> {
                            null
                        }
                    }
                }.maxOrNull()
        return AdoptResult(ackEpoch = ackEpoch, freshChains = fresh)
    }

    /** Records a distribution attempt in the outbox (the send path's bookkeeping; no lock needed —
     *  a single idempotent row upsert). */
    suspend fun markKeySent(
        groupId: String,
        memberId: String,
        epoch: Int,
        now: Long,
    ) {
        store.markKeySent(groupId, memberId, epoch, now)
    }

    /** The outbox row for one member, or null when never distributed. */
    suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendState? = store.keySend(groupId, memberId)

    /** One sealed group-form frame; [minted] non-null when this seal started a fresh epoch — the caller
     *  MUST distribute it to the roster (and record the outbox rows) before flooding the frame. */
    class SealedGroup(
        val env: EncEnvelope,
        val minted: GroupSeed?,
    )

    /**
     * Seals one outbound group frame under our send chain for [groupId], minting a fresh epoch when
     * the advance rules fire ([GroupRatchetEngine.needsNewEpoch]: none yet / count / age — the forced
     * cases arrive here as deleted chains). Runs read → mint → seal → persist atomically under the
     * shared lock; distribution of a minted seed happens strictly AFTER (the caller seals per-member
     * ctl DMs via [RatchetSessions.sealDm], which takes this same lock).
     */
    suspend fun sealGroup(
        groupId: String,
        selfNodeId: String,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
    ): SealedGroup? =
        locked {
            var chain = store.sendChain(groupId)
            var minted: GroupSeed? = null
            if (engine.needsNewEpoch(chain, now)) {
                chain = engine.mint(groupId, selfNodeId, prevEpoch = chain?.epoch ?: 0, now = now)
                minted = GroupSeed(epoch = chain.epoch, seed = chain.seed, mintedAt = chain.mintedAt)
            }
            val sealed = engine.seal(checkNotNull(chain), plaintext, aad) ?: return@locked null
            store.commitSend(sealed.chain)
            SealedGroup(sealed.toEnvelope(), minted)
        }

    /** Our retained seeds for [groupId] (current + draining previous), newest first — the
     *  re-distribution payload for key requests and proactive re-sends. */
    suspend fun currentSeeds(groupId: String): List<GroupSeed> =
        locked {
            store.sendChains(groupId).map { GroupSeed(epoch = it.epoch, seed = it.seed, mintedAt = it.mintedAt) }
        }

    /** Stamps a member's `CTL_GROUP_KEY_ACK` into the outbox (see [adoptSeeds] for the lock posture). */
    suspend fun onKeyAck(
        groupId: String,
        memberId: String,
        epoch: Int,
        now: Long,
    ) {
        store.markKeyAcked(groupId, memberId, epoch, now)
    }

    /** True when the (group, sender) adoption budget allows one more; records it. */
    private fun recordAdoption(
        groupId: String,
        senderId: String,
        now: Long,
    ): Boolean =
        synchronized(adoptionTimes) {
            val times = adoptionTimes.getOrPut(groupId to senderId) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() >= ADOPTION_WINDOW_MS) times.removeFirst()
            if (times.size >= MAX_ADOPTIONS_PER_DAY) return@synchronized false
            times.addLast(now)
            true
        }

    /**
     * Records an undecryptable group frame ([GroupRatchetEngine.OpenOutcome.Failed.NO_KEY] /
     * [GroupRatchetEngine.OpenOutcome.Failed.AEAD_FAIL]) and decides whether a key request toward that
     * sender is due: at least [REQUEST_DISTINCT_FRAMES] **distinct** frame ids (custody re-serves one
     * frame endlessly — a single stuck frame must not trigger anything), and not more often than
     * [KEY_REQUEST_MIN_INTERVAL_MS] per (group, sender). Check-only — [markKeyRequested] stamps the
     * floor once the request actually went out (the DM noteUndecryptable/sealResetDm split).
     */
    fun noteUndecryptable(
        groupId: String,
        senderId: String,
        frameId: String,
        now: Long,
    ): Boolean {
        val key = groupId to senderId
        val distinct =
            synchronized(undecryptable) {
                val ids = undecryptable.getOrPut(key) { LinkedHashSet() }
                ids.add(frameId)
                while (ids.size > REQUEST_TRACKED_FRAMES) ids.remove(ids.first())
                ids.size
            }
        if (distinct < REQUEST_DISTINCT_FRAMES) return false
        return synchronized(lastKeyRequestAt) { now - (lastKeyRequestAt[key] ?: 0L) >= KEY_REQUEST_MIN_INTERVAL_MS }
    }

    /** Stamps the outbound key-request floor and resets the heuristic (the request is on its way). */
    fun markKeyRequested(
        groupId: String,
        senderId: String,
        now: Long,
    ) {
        val key = groupId to senderId
        synchronized(lastKeyRequestAt) { lastKeyRequestAt[key] = now }
        synchronized(undecryptable) { undecryptable.remove(key) }
    }

    /** Retention GC passthrough (wired into the existing sweep loops beside the DM facade's). */
    suspend fun sweep(now: Long) = locked { store.sweep(now) }

    companion object {
        /** New-chain adoptions per (group, sender) per day — count + age + leave + wipe all fit. */
        const val MAX_ADOPTIONS_PER_DAY = 4

        /** The adoption rate limit's sliding window. */
        const val ADOPTION_WINDOW_MS = 24 * 60 * 60_000L

        /** Distinct undecryptable frames per (group, sender) before a key request fires. */
        const val REQUEST_DISTINCT_FRAMES = 3

        /** Bound on the per-(group, sender) undecryptable-id LRU. */
        const val REQUEST_TRACKED_FRAMES = 8

        /** Outbound key-request floor per (group, sender) — cheaper + non-destructive vs the DM's 6 h reset. */
        const val KEY_REQUEST_MIN_INTERVAL_MS = 60 * 60_000L

        /** Frames older than this never feed the heuristic (mirrors custody's dead-on-arrival guard). */
        const val REQUEST_MAX_FRAME_AGE_MS = 48 * 60 * 60_000L
    }
}

/** The group-form v2 envelope for one sealed group frame. */
internal fun GroupRatchetEngine.SealResult.toEnvelope(): EncEnvelope =
    EncEnvelope(
        v = EncEnvelope.VERSION_RATCHET,
        nonce = nonce,
        ct = ct,
        keys = emptyList(),
        g = GroupRatchetHeader(se = header.se, n = header.n),
    )
