package app.getknit.knit.mesh.spool

import java.util.concurrent.ConcurrentHashMap

/**
 * When an attachment's bytes may be held back from a spool because the radios are still carrying them
 * (`docs/SPOOL_PROTOCOL.md` §9.5's push half). Pure like [ScopeFrames]/[GroupRootPolicy] — reachability,
 * the radio evidence and the clock are all injected — so the whole rule set is unit-testable with
 * fixtures and this class stays free of Room, Android and [app.getknit.knit.mesh.MeshTransport].
 *
 * **The gate is a deferral, never a veto, and it must be self-reversing.** It holds bytes back only
 * while positive evidence says the mesh is carrying them, and re-opens by itself the moment that
 * evidence lapses — a peer that wanders out of range is uploaded to on the next heal round. That
 * asymmetry is the whole design: under-deferring costs relay bytes, over-deferring strands an image
 * permanently, so every uncertain case resolves to *push*. In particular a fresh process defers
 * nothing, because [lastSeen] starts empty.
 *
 * That is also why the delivery tick alone is not the rule. An ack is permanent, so gating on it would
 * strand an attachment whose frame was acked but whose *bytes* were never pulled — a real case, since
 * the blob rides a separate demand-driven [app.getknit.knit.mesh.BlobExchange] fetch, not the frame.
 * The two conditions do different jobs and both are needed:
 *
 * - **[reachable] is the presence plane** ([app.getknit.knit.mesh.MeshTransport.reachable]), which is
 *   cue-driven and includes peers we hold no data path to at all. On its own it would defer into a
 *   black hole.
 * - **[carriedByRadio] is proof a short-range data path actually worked** for this attachment's own
 *   conversation. The *plane* is load-bearing, not the delivery: since the Internet plane carries both
 *   frames and receipts, a pair can reach each other end-to-end across a spool from anywhere, and
 *   counting that would make the gate defer on the very plane the push feeds. LoRa is excluded too — a
 *   board carries a frame and never a blob — which is the same reason [reachable] is narrowed to the
 *   short-range set upstream.
 *
 * [carriedByRadio] asks one question from whichever end of the DM this node is. **We authored it:** did
 * the recipient's receipt come back over a short-range radio. **They authored it:** did their message
 * arrive here over one. The two readings are the same fact — these bytes already crossed a radio
 * between this scope's only two members — and both are per-recipient and paired with the expiring
 * sighting, so either way the gate stays a delay. Without the second reading a recipient re-uploaded
 * every photo it had just pulled off a BLE link, which is precisely the second copy this class exists
 * to prevent.
 *
 * The receiving end has a third reading, and it is the direct one: **the bytes themselves came off a
 * radio** ([noteRadioArrival], from the transport's file channel — `BlobExchange`'s only source). The
 * row's plane is a proxy for that, and it fails in two orderings the recipient really sees. The frame
 * can come off a spool before the radio delivers it — a BLE connect takes seconds where a live socket
 * takes milliseconds — so the row says `Internet` while the bytes still cross the radio, because the
 * sender deferred. And the bytes can land *before the row exists*: custody captures the frame and asks
 * for its blob before the sealed content is opened and persisted, so a neighbour's serve, a spool
 * digest and the row's commit are three racing writers, and the round that finds the bytes in hand may
 * find no row yet. In both, the radio arrival is the evidence the row was standing in for; it is noted
 * before the bytes are stored, so there is no round in which they are held and unexplained. In memory
 * like [lastSeen], for the same reason: losing it only means deferring less.
 *
 * A *missing* answer means two different things on the authoring end, and reading them as one is what
 * made this gate never fire for the case it exists for. `ScopeSync.onCustodyChanged` runs a round on
 * the *send itself*, so the first time [defer] is asked about a fresh photo the recipient's receipt is
 * still a round trip away and cannot exist — "the radios have not finished carrying this" arriving as
 * "the radios never carried this". [ackGraceMs] separates them: for that long after the frame was sent,
 * an attachment on a message we authored ([authoredHere]) holds on the sighting alone, and after it the
 * ordinary rule resumes. The receiving end needs no grace, since its row's plane is known the instant
 * the row exists. Both halves still expire by themselves — the sighting lapses, the frame ages — so the
 * deferral stays self-reversing, and reachability never holds bytes for the whole window, which is the
 * black hole this class's [reachable] note warns about.
 *
 * Two exclusions fall out of the rules rather than being spelled out, and both are the safe direction:
 * a **carried** frame is nobody's message here — a carrier holds the sealed bytes and no message row at
 * all — and an **avatar** (a sealed `CTL_PROFILE` frame) writes `PeerEntity` and no message row either,
 * so [carriedByRadio] reads false for both and, absent a radio arrival, they push. Neither should be
 * "fixed" into a deferral without a data-path signal to justify it. A peer's avatar *pulled* over a radio
 * is the one case the arrival memo reaches, and it is harmless: the owner's own copy never arrived
 * anywhere, so the owner pushes it regardless, and in a DM scope there is nobody else to serve.
 */
class AttachmentDeferPolicy(
    // Node ids on the presence plane right now, sampled per call — the smoothed `reachable` set, not the
    // ≤1 live data-path link, so an ephemeral sync rotation doesn't read as a peer leaving.
    private val reachable: () -> Set<String>,
    // Whether this attachment's message crossed a short-range radio between us and the scope's peer —
    // our own send acked over one, or their send that arrived over one (`MessageEntity.receivedVia`,
    // first-evidence-wins in both readings).
    private val carriedByRadio: suspend (aHash: String, peerId: String) -> Boolean,
    // Whether WE authored any message naming this attachment, acked or not — the grace's subject, and
    // nothing else. Only consulted inside [ackGraceMs], and only after [carriedByRadio] said no, so the
    // settled case still costs one read.
    private val authoredHere: suspend (aHash: String) -> Boolean,
    // The mesh custody TTL (`ForwardRepository.DEFAULT_TTL_MS`), injected rather than imported so this
    // layer keeps no dependency on the data layer. Bounds [lastCallMs] below.
    private val custodyTtlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = RADIO_WINDOW_MS,
    private val lastCallMs: Long = LAST_CALL_MS,
    private val ackGraceMs: Long = ACK_GRACE_MS,
    private val maxRadioArrivals: Int = MAX_RADIO_ARRIVALS,
) {
    // nodeId -> when we last saw it on the presence plane. In memory by design: the plane persists
    // nothing (ADR 019), and losing this on restart only means deferring less. Bounded by construction —
    // [noteReachable] drops anything past the window, so it holds at most the recent neighbour set.
    private val lastSeen = ConcurrentHashMap<String, Long>()

    // Ciphertext hashes whose bytes reached this node over a short-range radio, newest-noted last. A fact,
    // not a stamp, so nothing here expires: the sighting half is what lapses. Capped oldest-first so a
    // blob flood cannot grow it; an entry evicted early only means deferring less. Guarded by itself.
    private val radioArrivals =
        object : LinkedHashMap<String, Unit>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > maxRadioArrivals
        }

    /**
     * The bytes for [aHash] arrived over a short-range radio. Called from the transport's file channel
     * *before* the bytes are stored, so a heal round can never find them held without this on record.
     */
    fun noteRadioArrival(aHash: String) {
        synchronized(radioArrivals) { radioArrivals[aHash] = Unit }
    }

    private fun arrivedOverRadio(aHash: String): Boolean = synchronized(radioArrivals) { aHash in radioArrivals }

    /**
     * Whether [ref]'s bytes should be held back from this round's push for [scope]. False — push, today's
     * behaviour — for everything the rules below don't positively cover.
     *
     * Group scopes never defer. Their delivery tick is best-effort by construction: `applySealedReceipt`
     * flips one boolean on the first tick from *any* member, so "acked" never means "every member holds
     * it", and deferring on it would silently strand whoever wasn't reached.
     */
    @Suppress("ReturnCount") // one guard per rule, `ScopeFrames.open`'s shape; a nested pyramid hides them
    suspend fun defer(
        scope: Scope,
        ref: ScopeAttachments.Ref,
    ): Boolean {
        val now = clock()
        noteReachable(now)
        val peerId = scope.peerId ?: return false
        // Last call. Deferring is only safe while the frame is still in custody to drive a later push:
        // once it ages out, `ScopeAttachments.references` stops naming the attachment at all and the
        // chance to relay those bytes is gone for good. So stop deferring before that edge, not at it.
        if (ref.sentAt + custodyTtlMs - now <= lastCallMs) return false
        val seen = lastSeen[peerId] ?: return false
        if (now - seen > windowMs) return false
        // The direct evidence first: no read, and it is what the row's plane stands in for.
        if (arrivedOverRadio(ref.aHash) || carriedByRadio(ref.aHash, peerId)) return true
        // Too early to tell. A frame this young cannot have been acked yet — the receipt is a round trip
        // away, and the round asking us here is the one the send itself woke — so a missing ack is not
        // evidence the radios failed. Bounded by the frame's own age, so it re-opens with no new local
        // activity, and it covers only a send of ours: nothing else is waiting on an ack at all.
        return now - ref.sentAt < ackGraceMs && authoredHere(ref.aHash)
    }

    /** Stamps everyone currently reachable and forgets whoever can no longer justify a deferral. */
    private fun noteReachable(now: Long) {
        reachable().forEach { lastSeen[it] = now }
        lastSeen.entries.removeIf { now - it.value > windowMs }
    }

    companion object {
        /**
         * How long after the last sighting a peer still counts as radio-carrying. Comfortably above the
         * cue plane's own quiet periods — the BLE scan floors to ~2 min in a settled clique and a dozing
         * NAN peer can go dark for ~30 s ICM windows — so ordinary radio silence doesn't read as
         * departure, while a peer genuinely gone is uploaded to within a few heal rounds.
         */
        const val RADIO_WINDOW_MS = 15 * 60_000L

        /** How long before a frame leaves custody we stop deferring its attachment and push regardless. */
        const val LAST_CALL_MS = 2 * 60 * 60_000L

        /**
         * How long after a frame was sent its missing ack still reads as "not yet" rather than "never".
         * One `ScopeSync.TICK_INTERVAL_MS`, which is comfortably more than a co-located deliver-and-ack
         * round trip over BLE or NAN and the same order as ADR 2026-09.y5f3's ride deadline. It is also
         * the whole of the new cost: an attachment whose peer is in sight but whose radios never
         * delivered reaches a relay one grace plus one tick later than it used to.
         */
        const val ACK_GRACE_MS = 60_000L

        /**
         * How many radio arrivals are remembered. A deferral only matters while the frame is in custody
         * (24 h) and the peer in sight (15 min), and a phone pulls a few dozen photos over the radios in
         * that time at the very most; past the cap the oldest is forgotten and its bytes simply push.
         */
        const val MAX_RADIO_ARRIVALS = 256
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
