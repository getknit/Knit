package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto

/**
 * The scope-key material for one DM peer, already exported out of the ratchet layer — these are
 * **pairwise roots** (`HKDF(sessionRoot, "knit/dm/v2/export/root")`), never raw session secrets, so
 * session state stays behind `RatchetSessions` and its mutex.
 *
 * [prevPairwiseRoot] is the retiring session's export, live until [prevRootExpiresAt]: a replaced
 * session yields a new scope, and the old one stays derivable for the ratchet's drain window
 * (`docs/SPOOL_PROTOCOL.md` §3.1) so blobs already at a spool are still reachable.
 */
class ScopeRoots(
    val peerId: String,
    val pairwiseRoot: ByteArray,
    val prevPairwiseRoot: ByteArray? = null,
    val prevRootExpiresAt: Long = 0L,
)

/**
 * Derives the scope table: one scope per **accepted** DM peer with a live ratchet session, plus that
 * peer's retiring scope while its drain window is open. Pure — identity, ratchet state, and the
 * acceptance rule are all injected suspend seams, so the derivation is unit-testable with fixtures and
 * carries no Android or Room dependency.
 *
 * Group scopes are absent by design at this milestone: they need the shared group root
 * (`GroupKeyPayload.gr`), which ships with the group-scope milestone (spec §3.2, `memory/roadmap.md`).
 *
 * Bounds are constants here rather than per-conversation state because the signed scope-config ctl
 * (`CTL_SCOPE_CONFIG`, spec §5) is not on the wire yet; when it lands, [bounds] becomes a per-scope
 * lookup and these defaults become the fallback. They are the spec's §12 defaults so a stock spool
 * clamps them to themselves.
 */
class ScopeRegistry(
    private val selfId: suspend () -> String,
    private val roots: suspend () -> List<ScopeRoots>,
    private val isAccepted: suspend (String) -> Boolean,
    private val bounds: ScopeBounds = DEFAULT_BOUNDS,
) {
    /** Every scope this device participates in at [now], newest-session first, de-duplicated by id. */
    suspend fun scopes(now: Long): List<Scope> {
        val me = selfId()
        return roots()
            .filter { isAccepted(it.peerId) }
            .flatMap { entry ->
                buildList {
                    add(scopeFor(me, entry.peerId, entry.pairwiseRoot, retiring = false))
                    val prev = entry.prevPairwiseRoot
                    if (prev != null && entry.prevRootExpiresAt > now) {
                        add(scopeFor(me, entry.peerId, prev, retiring = true))
                    }
                }
            }.distinctBy { it.idHex }
    }

    private fun scopeFor(
        selfId: String,
        peerId: String,
        pairwiseRoot: ByteArray,
        retiring: Boolean,
    ) = Scope(
        id = ScopeCrypto.dmScopeId(pairwiseRoot, selfId, peerId),
        keys = ScopeCrypto.dmSealKeys(pairwiseRoot, selfId, peerId),
        peerId = peerId,
        bounds = bounds,
        retiring = retiring,
    )

    companion object {
        /** Spec §12: 2× the mesh custody TTL — the rotation drain window. */
        const val DEFAULT_TTL_MS = 48 * 60 * 60_000L

        /** Spec §12: 2× the mesh's 200-per-sender custody bucket. */
        const val DEFAULT_MAX_FRAMES = 400

        /** Spec §12: comfortably above any mesh frame. */
        const val DEFAULT_MAX_BLOB = 64 * 1024

        val DEFAULT_BOUNDS =
            ScopeBounds(maxFrames = DEFAULT_MAX_FRAMES, ttlMs = DEFAULT_TTL_MS, maxBlob = DEFAULT_MAX_BLOB)
    }
}
