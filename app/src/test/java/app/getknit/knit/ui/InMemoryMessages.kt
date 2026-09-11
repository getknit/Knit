package app.getknit.knit.ui

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher

/**
 * A real [MessageRepository] over an in-memory [KnitDatabase], for the ViewModel tests whose subject reads the
 * messages table through per-thread summary queries. Stubbing those queries with hand-computed answers would
 * restate the SQL in the test and prove nothing about it; running the SQL is the point.
 *
 * Built the way `RoomDbTest` builds its database — no driver, so Room falls back to Robolectric's framework
 * SQLite; never `KnitDatabase.build()` — with one addition: Room's own work (queries, Flow re-emissions, the
 * invalidation refresh after a write) rides [dispatcher] rather than `Dispatchers.IO`. With the unconfined
 * dispatcher a test installs as Main, a write then runs the invalidation, every re-query and the ViewModel's
 * fold inline, so `state.value` is fresh before [add] returns and `advanceUntilIdle()` keeps meaning what it
 * meant when the messages were a `MutableStateFlow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryMessages(
    dispatcher: TestDispatcher,
) : AutoCloseable {
    val db: KnitDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher)
            .build()

    val repo = MessageRepository(db.messageDao())

    private val seeded = linkedSetOf<String>()

    /** Upserts [rows] on top of what is there — the `messagesFlow.value + msg(...)` shape. */
    suspend fun add(vararg rows: MessageEntity) {
        for (row in rows) {
            repo.save(row)
            seeded += row.id
        }
    }

    /** Replaces everything this helper seeded with [rows] — the `messagesFlow.value = listOf(...)` shape. */
    suspend fun set(vararg rows: MessageEntity) {
        for (id in seeded) repo.delete(id)
        seeded.clear()
        add(*rows)
    }

    override fun close() = db.close()
}
