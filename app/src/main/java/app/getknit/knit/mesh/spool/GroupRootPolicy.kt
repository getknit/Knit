package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.protocol.GroupRootPayload
import java.security.SecureRandom

/**
 * One group's shared-root state as stored (`docs/SPOOL_PROTOCOL.md` §3.2). [root] is null while the row
 * exists only to hold [firstEligibleAt] — the mint-grace clock has to be persistent, or a device that
 * restarts hourly never reaches the end of its grace and never mints.
 *
 * [prevRoot]/[prevVersion] are the retiring lineage, kept until [prevExpiresAt] so a rotation doesn't
 * strand the blobs already pushed under the old scope id (the DM `prevRoot` drain, spec §3.3). They also
 * cover the *losing* side of a competing v1 mint, which is the same situation seen from the other end.
 *
 * [remintDueAt] is stamped when a departure is processed and cleared by the mint that answers it —
 * recording the obligation separately from acting on it is what makes rotation crash-safe and what lets
 * the grace cover a deterministic re-minter who never comes back.
 */
class GroupRootState(
    val groupId: String,
    val root: ByteArray? = null,
    val version: Int = 0,
    val minter: String = "",
    val prevRoot: ByteArray? = null,
    val prevVersion: Int = 0,
    val prevExpiresAt: Long = 0L,
    val firstEligibleAt: Long = 0L,
    val remintDueAt: Long = 0L,
)

/**
 * The group-root rules of `docs/SPOOL_PROTOCOL.md` §3.2 — ordering, who mints and when, what may be
 * adopted, and the rotation transition. Pure: no IO, no Android, no clock of its own, so the whole
 * convergence story is unit-testable against fixtures.
 *
 * The shape to keep in mind: **minting is damped, adoption is not.** Several members reaching the end of
 * their grace at once is a normal, self-healing event — the `(version, minter)` order collapses the
 * lineages. Refusing to *adopt* a strictly-greater root would be the opposite: the device keeps
 * gossiping a root everyone else ignores and never converges again, so there is deliberately no
 * adoption rate limit here. Outbound chatter is bounded on the send side instead.
 *
 * Every function is deterministic except [newRoot], the one CSPRNG call (`GroupRatchetCrypto`'s shape).
 */
object GroupRootPolicy {
    /** Raw root width — the HKDF ikm the group scope id and seal keys derive from (spec §3.3). */
    const val ROOT_BYTES = 32

    /** How long a non-preferred minter waits for a gossiped root before minting its own (spec §12). */
    const val MINT_GRACE_MS = 6 * 60 * 60_000L

    /** How long a rotated-away lineage stays derivable, mirroring the DM prev-root window (spec §3.3). */
    const val DRAIN_MS = 48 * 60 * 60_000L

    /**
     * The adoption ceiling (spec §3.2). A legitimate version never exceeds the founding roster size —
     * one mint plus at most `size - 1` departures, and rosters never grow — so this is double the
     * model's maximum. Without it a single grief-mint near `Int.MAX_VALUE` freezes the scope forever.
     */
    const val MAX_ROOT_VERSION = 16

    /** Per-adoption version jump bound: a device that missed every departure of a full roster still fits. */
    const val MAX_ROOT_VERSION_JUMP = 8

    private val random = SecureRandom()

    /**
     * A fresh random group root. Its own generator rather than the group ratchet's `newSeed` because the
     * two secrets have unrelated lifetimes and blast radii — this one keys the *outer*, scope-static seal
     * (spec §4.2) and rotates only on departure, while an epoch seed rotates constantly and is the inner
     * scheme's forward-secrecy unit.
     */
    fun newRoot(): ByteArray = ByteArray(ROOT_BYTES).also(random::nextBytes)

    /** Strictly-greater `(version, minter)` — the total order every member resolves lineages by. */
    fun isNewer(
        version: Int,
        minter: String,
        held: GroupRootState?,
    ): Boolean {
        val current = held?.takeIf { it.root != null } ?: return true
        return version > current.version || (version == current.version && minter > current.minter)
    }

    /**
     * Who mints without waiting: the creator if still a member, else the smallest remaining node id.
     * Null only for an empty roster (a group we hold no members for — nothing to mint into).
     */
    fun preferredMinter(
        createdBy: String,
        members: Collection<String>,
    ): String? = if (createdBy in members) createdBy else members.minOrNull()

    /**
     * The version this device should mint now, or null for "nothing to do". Covers both mints with one
     * rule (spec §3.2): version 1 when no root is held, `version + 1` when a departure stamped a re-mint
     * due. The preferred minter acts immediately; anyone else waits [MINT_GRACE_MS] from the stamp that
     * started the wait — which is why a caller must persist that stamp *before* the first check, and why
     * an unstamped state deliberately mints nothing.
     *
     * The caller supplies the eligibility gates (plane enabled, group held and not left, group fully
     * ratchet-capable); this decides only the timing.
     */
    fun mintDue(
        state: GroupRootState?,
        selfId: String,
        preferredMinter: String?,
        now: Long,
    ): Int? {
        val holdsRoot = state?.root != null
        val version =
            when {
                !holdsRoot -> 1
                state.remintDueAt > 0L -> state.version + 1
                else -> return null
            }
        if (version > MAX_ROOT_VERSION) return null
        // An unstamped wait (0) never elapses: the caller persists the stamp before the first check, and
        // treating "no clock yet" as "grace is over" would make every restart a fresh lineage.
        val waitingSince = if (holdsRoot) state.remintDueAt else state?.firstEligibleAt ?: 0L
        val graceElapsed = waitingSince > 0L && now - waitingSince >= MINT_GRACE_MS
        return version.takeIf { selfId == preferredMinter || graceElapsed }
    }

    /**
     * Whether a gossiped root may be adopted over [held]: well-formed, inside the version ceiling and
     * jump bound, minted by someone in the pinned founding roster, and strictly newer. The roster check
     * is not ceremony — without it any member wins every tie forever by naming a lexicographically
     * maximal minter id that belongs to nobody.
     *
     * The *sender* gate (in the founding roster, not departed, group not left) is the caller's, matching
     * the seed path exactly.
     */
    fun adoptable(
        gr: GroupRootPayload,
        foundingRoster: Set<String>,
        held: GroupRootState?,
    ): Boolean {
        if (gr.root.size != ROOT_BYTES) return false
        if (gr.version < 1 || gr.version > MAX_ROOT_VERSION) return false
        val heldVersion = held?.takeIf { it.root != null }?.version ?: 0
        if (gr.version > heldVersion + MAX_ROOT_VERSION_JUMP) return false
        if (gr.minter !in foundingRoster) return false
        return isNewer(gr.version, gr.minter, held)
    }

    /**
     * The state after adopting or minting [root]: the outgoing lineage becomes the draining previous one
     * and the re-mint obligation is discharged. [firstEligibleAt] survives so a later rotation back to
     * "no root" (there is none today) could not silently reset a grace clock.
     */
    fun rotated(
        state: GroupRootState?,
        groupId: String,
        root: ByteArray,
        version: Int,
        minter: String,
        now: Long,
    ): GroupRootState {
        val outgoing = state?.takeIf { it.root != null }
        return GroupRootState(
            groupId = groupId,
            root = root,
            version = version,
            minter = minter,
            prevRoot = outgoing?.root,
            prevVersion = outgoing?.version ?: 0,
            prevExpiresAt = if (outgoing != null) now + DRAIN_MS else 0L,
            firstEligibleAt = state?.firstEligibleAt.takeIf { it != null && it > 0L } ?: now,
            remintDueAt = 0L,
        )
    }
}
