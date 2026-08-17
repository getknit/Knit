package app.getknit.knit.mesh.crypto.ratchet

/**
 * Opens the enclosing database transaction for a ratchet-session critical section.
 *
 * **Why this exists — the deadlock it removes.** Every `mutex.withLock` block in [RatchetSessions] and
 * [GroupRatchetSessions] touches the store, and Room over SQLCipher serves this app through a *single*
 * connection. So two orders were reachable at once:
 *
 * - `db.withTransaction { commitOpen(…) }` — the inbound decrypt path: **transaction, then mutex**.
 * - `sealDm` / `sealGroup` / `currentSeeds` / `sweep` / `exportedRoots` called with no enclosing
 *   transaction — **mutex, then (implicitly) the connection**.
 *
 * Run those concurrently and they deadlock: the decrypt path holds the connection and waits for the
 * mutex while the seal path holds the mutex and waits for the connection. Both are *suspended*
 * coroutines, so neither shows up in a thread dump — the only visible symptom is that every later DB
 * user blocks forever and the app ANRs on the next thing that reads the database. That is exactly what
 * wedged a lab device on the M4 smoke.
 *
 * The class doc used to state the order as a rule for callers to follow. It is enforced here instead:
 * the facades take the transaction *before* the lock on every path, so the one global order holds
 * whether or not a caller wrapped the call. Room's `withTransaction` is reentrant for the same
 * coroutine, so a caller that already opened one (the decrypt path) simply joins it.
 *
 * Kept as an interface rather than a `db` reference so both facades stay Android-free and plain-JVM
 * testable — the same lambda-mediation the rest of the mesh layer uses.
 */
interface SessionTransactor {
    suspend fun <T> transact(block: suspend () -> T): T

    companion object {
        /**
         * Pass-through, for rigs with no database (the pure engine/session tests). Safe there and only
         * there: with no shared connection to contend for, there is no order to get wrong.
         */
        val None: SessionTransactor =
            object : SessionTransactor {
                override suspend fun <T> transact(block: suspend () -> T): T = block()
            }
    }
}
