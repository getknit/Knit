package app.getknit.knit.data.ratchet

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One group's shared **root** for the spool plane's group scopes (`docs/SPOOL_PROTOCOL.md` §3.2) — the
 * secret the scope id and seal keys derive from, mirrored from
 * [app.getknit.knit.mesh.spool.GroupRootState]. It sits beside the group-ratchet tables because it rides
 * the same `CTL_GROUP_KEY` channel and dies on the same leave/delete, but it is *not* ratchet state: no
 * chain, no epoch, no forward secrecy of its own (§4.2 — the outer seal is scope-static by design).
 *
 * [root] is null while the row exists only to carry [firstEligibleAt]. That stamp is the mint-grace clock
 * (§3.2) and has to be **persistent**: a process-lifetime timer restarts with the app, so a
 * frequently-restarted device would never reach the end of its grace and would never mint.
 *
 * [prevRoot]/[prevVersion] are the lineage a rotation retired, derivable until [prevExpiresAt] so the
 * blobs already pushed under the old scope id stay reachable for the drain window — and equally the
 * losing side of a competing v1 mint, which is the same situation from the other end.
 *
 * [remintDueAt] records that a processed departure obliges a re-mint; the mint that answers it clears the
 * stamp. Recording the obligation separately from acting on it is what makes rotation crash-safe.
 *
 * Root material lives in the SQLCipher-encrypted DB, the same at-rest posture as the ratchet tables. A DB
 * wipe loses the root by design — the wiped device re-adopts the current one from the first gossiping
 * `CTL_GROUP_KEY` it receives.
 */
@Entity(tableName = "group_roots")
data class GroupRootEntity(
    @PrimaryKey val groupId: String,
    val root: ByteArray? = null,
    val version: Int = 0,
    val minter: String = "",
    val prevRoot: ByteArray? = null,
    val prevVersion: Int = 0,
    val prevExpiresAt: Long = 0L,
    val firstEligibleAt: Long = 0L,
    val remintDueAt: Long = 0L,
)
