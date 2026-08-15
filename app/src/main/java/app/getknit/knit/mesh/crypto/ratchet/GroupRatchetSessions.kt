package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.GroupSeed
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session service for the group sender-key ratchet (crypto scheme v3,
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
 * Callers keep the global order: Room transaction OUTER, the shared mutex INNER.
 */
class GroupRatchetSessions(
    private val store: GroupRatchetStore,
    private val engine: GroupRatchetEngine = GroupRatchetEngine(),
    private val mutex: Mutex = Mutex(),
) {
    /** Adoption timestamps per (groupId, senderId) — the epoch-adoption rate limit's memory. */
    private val adoptionTimes = HashMap<Pair<String, String>, ArrayDeque<Long>>()

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
     * Phase one of a v3 decrypt: opens the frame against a state snapshot WITHOUT persisting anything.
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
     * callers MUST wrap this call in `db.withTransaction { }` when they persist anything alongside it.
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
        mutex.withLock {
            val header = headerOf(wireHeader)
            val outcome = engine.open(contextFor(groupId, senderId, header), header, nonce, ct, aad, now)
            if (outcome !is GroupRatchetEngine.OpenOutcome.Opened) return@withLock false
            store.applyOpen(groupId, senderId, outcome.delta, headerSe = header.se, headerN = header.n)
            onOpened()
            true
        }

    /**
     * Adopts the seeds of one inbound `CTL_GROUP_KEY` distribution and returns the highest epoch worth
     * acknowledging (null ⇒ nothing to ack). Idempotent per (epoch, mintedAt): a custody re-serve
     * re-acks (the sender's outbox converges even if the first ack was lost) without touching chain
     * position. New adoptions are rate-limited to [MAX_ADOPTIONS_PER_DAY] per (group, sender) — the
     * skipped-key-pump bound; legitimate advances (count, age, leave, wipe) fit under it.
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
    ): Int? =
        seeds
            .mapNotNull { seed ->
                val newest = store.recvChains(groupId, senderId, seed.epoch).firstOrNull()
                when (val outcome = engine.adoptSeed(newest, groupId, senderId, seed.epoch, seed.seed, seed.mintedAt, now)) {
                    is GroupRatchetEngine.AdoptOutcome.Adopt -> {
                        if (!recordAdoption(groupId, senderId, now)) return@mapNotNull null
                        store.insertRecvChain(outcome.recv)
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

    /** Retention GC passthrough (wired into the existing sweep loops beside the DM facade's). */
    suspend fun sweep(now: Long) = mutex.withLock { store.sweep(now) }

    companion object {
        /** New-chain adoptions per (group, sender) per day — count + age + leave + wipe all fit. */
        const val MAX_ADOPTIONS_PER_DAY = 4

        /** The adoption rate limit's sliding window. */
        const val ADOPTION_WINDOW_MS = 24 * 60 * 60_000L
    }
}

/** The v3 envelope for one sealed group frame (the seal side lands with the send phase). */
internal fun GroupRatchetEngine.SealResult.toEnvelope(): EncEnvelope =
    EncEnvelope(
        v = EncEnvelope.VERSION_GROUP_RATCHET,
        nonce = nonce,
        ct = ct,
        keys = emptyList(),
        g = GroupRatchetHeader(se = header.se, n = header.n),
    )
