package app.getknit.knit.mesh.spool

/**
 * Persistence seam for the shared group roots (`docs/SPOOL_PROTOCOL.md` §3.2), implemented by
 * `data/ratchet/GroupRootRepository` over Room and by in-memory fakes in tests — the
 * [app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore] pattern, same **transaction-agnostic**
 * contract: each method performs only its own row operation, and the caller wraps it in whatever
 * `db.withWriteTransaction` the surrounding mutation already owns.
 *
 * Roots live outside the (nullable, opt-in) [ScopeSync] lifetime on purpose: a device with the Internet
 * plane switched off still adopts and re-gossips the newest root it sees, which is what lets a root
 * cross a plane-off member sitting between two plane-on ones. Only *minting* is gated on the plane.
 */
interface GroupRootStore {
    /** One group's root state, or null when this device has never been eligible for it. */
    suspend fun find(groupId: String): GroupRootState?

    /** Every group root row — the scope table's group half reads this once per reconcile. */
    suspend fun all(): List<GroupRootState>

    /** Writes a minted or adopted root (see [GroupRootPolicy.rotated] for the transition). */
    suspend fun upsert(state: GroupRootState)

    /**
     * Stamps the mint-grace clock the first time this device becomes eligible for [groupId]; a no-op
     * once stamped. Persistent by design — a process-lifetime timer would restart with the app and a
     * frequently-restarted device would never reach the end of its grace.
     */
    suspend fun markEligible(
        groupId: String,
        at: Long,
    )

    /**
     * Records that a processed departure obliges a re-mint (spec §3.2). A no-op when no root is held
     * (nothing to rotate) or when the obligation is already recorded, so a re-served `groupleave` can't
     * push the grace deadline back.
     */
    suspend fun markRemintDue(
        groupId: String,
        at: Long,
    )

    /** Drops [groupId]'s root state — our own leave/delete, alongside the group-ratchet purge. */
    suspend fun purge(groupId: String)

    /** Retention GC on the existing sweep loops: clears drained previous lineages past their window. */
    suspend fun sweep(now: Long)
}
