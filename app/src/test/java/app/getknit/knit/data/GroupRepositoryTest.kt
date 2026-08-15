package app.getknit.knit.data

import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transactional group mutations had no dedicated test. Exercises `recordDeparture` (roster edit +
 * departed tombstone + status notice, all in one transaction) and `leave`/`delete` against the real DB.
 */
class GroupRepositoryTest : RoomDbTest() {
    private val groupRatchet by lazy { GroupRatchetRepository(db.groupRatchetDao(), clock = { 1L }) }

    private fun repo() = GroupRepository(db.groupDao(), MessageRepository(db.messageDao()), db, groupRatchet)

    private val messages get() = db.messageDao()

    private suspend fun seedGroup(
        id: String,
        members: List<String>,
        left: Boolean = false,
    ) = db.groupDao().upsert(
        GroupEntity(
            groupId = id,
            name = "",
            members = GroupMembersStore.encode(members),
            createdBy = members.first(),
            createdAt = 1L,
            left = left,
        ),
    )

    @Test
    fun `recordDeparture drops the member, tombstones them, and inserts a status notice`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            val group = db.groupDao().findById("g")!!
            assertEquals(listOf("a", "c"), GroupMembersStore.decode(group.members))
            assertTrue("b" in GroupMembersStore.decode(group.departed))
            val notice = messages.observeForConversation("g").first().single()
            assertEquals(MessageEntity.KIND_MEMBER_LEFT, notice.kind)
            assertEquals("b", notice.senderId)
            assertEquals(100L, notice.sentAt)
        }

    @Test
    fun `recordDeparture forces a rekey and drops the leaver's outbox row atomically`() =
        runTest {
            seedGroup("g", listOf("a", "b", "c"))
            groupRatchet.commitSend(
                GroupRatchetEngine.SendChain(
                    groupId = "g",
                    epoch = 1,
                    seed = ByteArray(32),
                    chainKey = ByteArray(32),
                    count = 3,
                    mintedAt = 1L,
                    export = ByteArray(32),
                ),
            )
            groupRatchet.markKeySent("g", "b", epoch = 1, at = 1L)
            groupRatchet.markKeySent("g", "c", epoch = 1, at = 1L)

            assertTrue(repo().recordDeparture("g", "b", leftAt = 100L))

            // Send chains die with the departure — the next send mints a fresh epoch that the leaver
            // never receives; the leaver stops being a distribution target, remaining members keep theirs.
            assertNull(groupRatchet.sendChain("g"))
            assertNull(groupRatchet.keySend("g", "b"))
            assertEquals(1, groupRatchet.keySend("g", "c")?.sentEpoch)
        }

    @Test
    fun `leave purges all group ratchet state`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            groupRatchet.commitSend(
                GroupRatchetEngine.SendChain(
                    groupId = "g",
                    epoch = 1,
                    seed = ByteArray(32),
                    chainKey = ByteArray(32),
                    count = 0,
                    mintedAt = 1L,
                    export = ByteArray(32),
                ),
            )
            groupRatchet.insertRecvChain(
                GroupRatchetEngine.RecvChain(
                    groupId = "g",
                    senderId = "b",
                    epoch = 1,
                    mintedAt = 1L,
                    chainKey = ByteArray(32),
                    next = 0,
                    lastUsedAt = 1L,
                ),
            )
            groupRatchet.markKeySent("g", "b", epoch = 1, at = 1L)

            repo().leave("g")

            assertNull(groupRatchet.sendChain("g"))
            assertTrue(groupRatchet.recvChains("g", "b", 1).isEmpty())
            assertNull(groupRatchet.keySend("g", "b"))
        }

    @Test
    fun `recordDeparture is a no-op for a non-member`() =
        runTest {
            seedGroup("g", listOf("a", "b"))

            assertFalse(repo().recordDeparture("g", "z", leftAt = 100L))

            assertEquals(listOf("a", "b"), GroupMembersStore.decode(db.groupDao().findById("g")!!.members))
            assertTrue(messages.observeForConversation("g").first().isEmpty())
        }

    @Test
    fun `recordDeparture is a no-op on a group we have already left`() =
        runTest {
            seedGroup("g", listOf("a", "b"), left = true)
            assertFalse(repo().recordDeparture("g", "b", leftAt = 100L))
        }

    @Test
    fun `leave tombstones the group and purges its messages`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            messages.upsert(MessageEntity(id = "m1", senderId = "a", conversationId = "g", body = "hi", sentAt = 1L))

            repo().leave("g")

            assertTrue(db.groupDao().findById("g")!!.left)
            assertFalse(messages.exists("m1"))
        }

    @Test
    fun `delete removes the group row and its messages`() =
        runTest {
            seedGroup("g", listOf("a", "b"))
            messages.upsert(MessageEntity(id = "m1", senderId = "a", conversationId = "g", body = "hi", sentAt = 1L))

            repo().delete("g")

            assertNull(db.groupDao().findById("g"))
            assertFalse(messages.exists("m1"))
        }
}
