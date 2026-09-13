package app.getknit.knit.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * What this phone has done for other people's messages, as persisted: the lifetime numbers behind the
 * Your mesh screen. [passedAlong] is every message frame this phone sent on for someone else;
 * [deliveredToRecipient] is the subset it sent over a live link to the very person the message was for
 * — a hand-off, not a confirmed delivery, which is why the screen never says "delivered". [since] is the
 * local clock at the first credit, 0 until there has been one.
 */
data class ContributionTotals(
    val passedAlong: Long = 0L,
    val deliveredToRecipient: Long = 0L,
    val since: Long = 0L,
) {
    companion object {
        val NONE = ContributionTotals()
    }
}

/**
 * The two-method slice of [SettingsStore] that `mesh/ContributionLedger` writes through — the
 * [NanAttachJournal] / [ModelLoadJournal] precedent, extracted as a seam so the ledger stays pure and
 * runs against an in-memory fake in its own test.
 *
 * Why two additive longs rather than the one-blob-per-key ledger of ADR 2026-09.7svb: that blob exists
 * because its payload is a list whose halves must stay consistent, while these are two monotonic counters
 * written in one DataStore transaction, already atomic. Making the write **additive** — the store adds the
 * deltas it is handed — means the ledger never reads before it writes, so a flush that lands before the
 * first read cannot clobber anything, and two flushes cannot race each other into a lost update.
 *
 * Why the DataStore at all: the numbers are counts, not identities (the node ids behind "people met" live
 * in the encrypted database, `MetPeerEntity`), and the settings DataStore is excluded from backup and
 * device transfer already (`res/xml/backup_rules.xml`), so nothing here ever leaves the phone.
 */
interface ContributionJournal {
    /** The persisted totals; re-emits on every write. */
    val contributionTotals: Flow<ContributionTotals>

    /**
     * Adds [passedAlong] and [deliveredToRecipient] to the persisted totals in ONE write, and stamps
     * [since] with [now] if it has never been stamped. Called on a slow tick, never per frame: every
     * DataStore write re-emits every `dataStore.data` collector in the app.
     */
    suspend fun addContributions(
        passedAlong: Long,
        deliveredToRecipient: Long,
        now: Long,
    )
}
