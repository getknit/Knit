package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.search.SearchQuery
import app.getknit.knit.ui.msg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executes the **real** [MessageDao.searchBodies] SQL over the in-memory database — which, being built by
 * Room's own `createAllTables`, carries the `messages_fts` virtual table and its content-sync triggers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageSearchDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    private suspend fun search(
        raw: String,
        conversations: Collection<String> = listOf("peer-1", "peer-2", Conversations.NEARBY, Conversations.MESHTASTIC),
        blocked: Set<String> = emptySet(),
        hideFlagged: Boolean = false,
        limit: Int = 50,
    ): List<String> =
        dao
            .searchBodies(SearchQuery.toMatch(raw)!!, conversations, blocked, hideFlagged, limit)
            .map { it.id }

    @Test
    fun `matches a token prefix, folding case and diacritics`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "Trailhead at dawn", id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "Café closes early", id = "m2"))

            assertEquals(listOf("m1"), search("trail"))
            assertEquals(listOf("m1"), search("TRAILHEAD"))
            assertEquals(listOf("m2"), search("cafe"))
            assertEquals(listOf("m2"), search("CAFÉ"))
            assertEquals("not a token start", emptyList<String>(), search("head"))
        }

    @Test
    fun `folds the scripts the tokenizer folds, and keeps a Hangul syllable whole`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "Привет из Москвы", id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "한국어 공부", id = "m2"))
            dao.upsert(msg("n1", 3, "peer-1", body = "東京に行く", id = "m3"))

            assertEquals(listOf("m1"), search("привет"))
            assertEquals(listOf("m1"), search("ПРИВЕТ"))
            assertEquals(listOf("m2"), search("한국"))
            assertEquals(listOf("m3"), search("東京"))
            assertEquals("no boundary inside a run of ideographs", emptyList<String>(), search("京"))
        }

    @Test
    fun `every token must match, in any order, anywhere in the body`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "found your water bottle", id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "water is over there", id = "m2"))

            assertEquals(listOf("m1"), search("bottle water"))
            assertEquals(listOf("m2", "m1"), search("water"))
        }

    @Test
    fun `status notices are never hits, even when their body matches`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "summit", id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "summit", kind = MessageEntity.KIND_GROUP_RENAMED, id = "n1"))
            dao.upsert(msg("n1", 3, "peer-1", body = "summit", kind = MessageEntity.KIND_FILE_TRANSFER, id = "n2"))

            assertEquals(listOf("m1"), search("summit"))
        }

    @Test
    fun `blocked senders and threads outside the allow-list are left out`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "hello there", id = "m1"))
            dao.upsert(msg("n2", 2, "peer-2", body = "hello again", id = "m2"))
            dao.upsert(msg("n3", 3, "stranger", body = "hello stranger", id = "m3"))

            assertEquals(listOf("m2", "m1"), search("hello"))
            assertEquals(listOf("m1"), search("hello", blocked = setOf("n2")))
            assertEquals(listOf("m2"), search("hello", conversations = listOf("peer-2")))
            assertEquals(emptyList<String>(), search("hello", conversations = listOf("nowhere")))
        }

    @Test
    fun `flagged text is hidden only while asked`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "flagged words", moderation = MessageEntity.MODERATION_TEXT_FLAGGED, id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "plain words", id = "m2"))

            assertEquals(listOf("m2"), search("words", hideFlagged = true))
            assertEquals(listOf("m2", "m1"), search("words", hideFlagged = false))
        }

    @Test
    fun `newest first with the id tiebreak, bounded by the limit`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "photo one", id = "m1"))
            dao.upsert(msg("n1", 2, "peer-1", body = "photo two", id = "m2"))
            dao.upsert(msg("n1", 2, "peer-1", body = "photo three", id = "m3"))

            assertEquals(listOf("m3", "m2", "m1"), search("photo"))
            assertEquals(listOf("m3", "m2"), search("photo", limit = 2))
        }

    @Test
    fun `the index follows deletes, updates and ignored inserts`() =
        runTest {
            dao.upsert(msg("n1", 1, "peer-1", body = "summit photos", id = "m1"))
            dao.deleteById("m1")
            assertEquals(emptyList<String>(), search("summit"))

            dao.upsert(msg("n1", 2, "peer-1", body = "summit photos", id = "m2"))
            dao.upsert(msg("n1", 2, "peer-1", body = "ridge photos", id = "m2"))
            assertEquals("the old body is gone", emptyList<String>(), search("summit"))
            assertEquals(listOf("m2"), search("ridge"))

            assertEquals(-1L, dao.insertIfAbsent(msg("n1", 2, "peer-1", body = "summit again", id = "m2")))
            assertEquals("an ignored insert indexes nothing", emptyList<String>(), search("summit"))
            assertEquals("and does not double-index the row", listOf("m2"), search("ridge photos"))
        }

    @Test
    fun `a heard Meshtastic post is found through its own thread`() =
        runTest {
            dao.upsert(
                msg("me", 1, Conversations.MESHTASTIC, body = "LongFast hello", originNode = 7, originName = "Bob", id = "h1"),
            )

            assertEquals(listOf("h1"), search("longfast"))
            assertEquals(emptyList<String>(), search("longfast", conversations = listOf(Conversations.NEARBY)))
        }
}
