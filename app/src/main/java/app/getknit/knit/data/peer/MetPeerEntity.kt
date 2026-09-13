package app.getknit.knit.data.peer

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A phone this one has been within radio range of: the lifetime union of `MeshController.neighbors`, one
 * row per node id, for the "people this phone has met" line on the Your mesh screen.
 *
 * It is its own table rather than a column on `peers` because `peers` is the wrong shape for the question.
 * A `peers` row is written for any authenticated profile, including one that walked here over several hops
 * from a phone this one never saw — "heard of", not "met" — and that table is capped with oldest-profile
 * eviction, so a met contact could vanish from the count when a busy room filled it. This table keys on
 * the one signal that means proximity (a short-range radio sighted the peer's own radio, ADR 2026-09.2ajk),
 * grows only by that signal, and evicts least-recently-met, so a burst of unauthenticated ids can never
 * push out a regular contact.
 *
 * [firstMetAt] is our own clock at the first sighting and never moves; [lastMetAt] moves on every sighting
 * and is the eviction key. Local only — nothing about it is ever framed and no digest folds over it. It
 * lives in this database rather than the settings DataStore because it is a list of node ids, and the
 * database is the encrypted store.
 */
@Entity(tableName = "met_peers", indices = [Index("lastMetAt")])
data class MetPeerEntity(
    @PrimaryKey val nodeId: String,
    val firstMetAt: Long,
    val lastMetAt: Long,
)
