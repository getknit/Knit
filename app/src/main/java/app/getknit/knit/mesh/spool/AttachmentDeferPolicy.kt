package app.getknit.knit.mesh.spool

import java.util.concurrent.ConcurrentHashMap

/**
 * When an attachment's bytes may be held back from a spool because the radios are still carrying them
 * (`docs/SPOOL_PROTOCOL.md` §9.5's push half). Pure like [ScopeFrames]/[GroupRootPolicy] — reachability,
 * the delivery tick and the clock are all injected — so the whole rule set is unit-testable with
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
 * - **[ackedBySender] is proof a data path actually worked** for this attachment's own conversation.
 *
 * Two exclusions fall out of the rules rather than being spelled out, and both are the safe direction:
 * a **carried** frame has no message row we authored, so a carrier always pushes; and an **avatar**
 * (a sealed `CTL_PROFILE` frame, which writes `PeerEntity` and no message row) always pushes too.
 * Neither should be "fixed" into a deferral without a delivery signal to justify it.
 */
class AttachmentDeferPolicy(
    // Node ids on the presence plane right now, sampled per call — the smoothed `reachable` set, not the
    // ≤1 live data-path link, so an ephemeral sync rotation doesn't read as a peer leaving.
    private val reachable: () -> Set<String>,
    // Whether a message WE authored names this attachment and has been acked (`MessageEntity.received`).
    private val ackedBySender: suspend (aHash: String) -> Boolean,
    // The mesh custody TTL (`ForwardRepository.DEFAULT_TTL_MS`), injected rather than imported so this
    // layer keeps no dependency on the data layer. Bounds [lastCallMs] below.
    private val custodyTtlMs: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = RADIO_WINDOW_MS,
    private val lastCallMs: Long = LAST_CALL_MS,
) {
    // nodeId -> when we last saw it on the presence plane. In memory by design: the plane persists
    // nothing (ADR 019), and losing this on restart only means deferring less. Bounded by construction —
    // [noteReachable] drops anything past the window, so it holds at most the recent neighbour set.
    private val lastSeen = ConcurrentHashMap<String, Long>()

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
        return ackedBySender(ref.aHash)
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
    }
}
