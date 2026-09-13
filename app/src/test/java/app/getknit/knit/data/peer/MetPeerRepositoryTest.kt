package app.getknit.knit.data.peer

import app.getknit.knit.data.RoomDbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real-SQL coverage for the met-peers table behind "people this phone has met": a repeat sighting keeps the
 * first stamp and moves the latest one, the count re-emits on a first sighting only, and the cap sheds the
 * least-recently-met rows first so a burst of strangers can never push out a regular contact.
 */
class MetPeerRepositoryTest : RoomDbTest() {
    private val dao get() = db.metPeerDao()

    @Test
    fun `a repeat sighting keeps firstMetAt and moves lastMetAt`() =
        runTest {
            val repo = MetPeerRepository(dao, db)
            repo.recordMet(listOf("a"), now = 10L)
            repo.recordMet(listOf("a"), now = 20L)
            val row = dao.find("a")
            assertNotNull(row)
            assertEquals(10L, row!!.firstMetAt)
            assertEquals(20L, row.lastMetAt)
        }

    @Test
    fun `observeCount grows on a first sighting and not on a repeat`() =
        runTest {
            val repo = MetPeerRepository(dao, db)
            assertEquals(0, repo.observeCount().first())
            repo.recordMet(setOf("a", "b"), now = 10L)
            assertEquals(2, repo.observeCount().first())
            repo.recordMet(setOf("a"), now = 20L)
            assertEquals(2, repo.observeCount().first())
            repo.recordMet(emptySet(), now = 30L)
            assertEquals(2, repo.observeCount().first())
        }

    @Test
    fun `the cap evicts the least-recently-met first`() =
        runTest {
            val repo = MetPeerRepository(dao, db, maxRows = 3)
            repo.recordMet(listOf("old"), now = 1L)
            repo.recordMet(listOf("regular"), now = 2L)
            repo.recordMet(listOf("mid"), now = 3L)
            // The regular contact is seen again: it is now the most recent, so the one-time strangers go first.
            repo.recordMet(listOf("regular"), now = 4L)
            repo.recordMet(listOf("new1", "new2"), now = 5L)
            assertEquals(3, dao.count())
            assertNull(dao.find("old"))
            assertNull(dao.find("mid"))
            assertNotNull(dao.find("regular"))
            assertNotNull(dao.find("new1"))
            assertNotNull(dao.find("new2"))
        }
}
