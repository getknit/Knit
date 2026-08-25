package app.getknit.knit.data.message

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.blob.BlobEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [MessageDao] SQL (finding #5): the `blobs` anti-join that drives attachment fetch and
 * the delivery-critical pending-key / received-flag mutations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDaoTest : RoomDbTest() {
    private val dao get() = db.messageDao()

    @Test
    fun `hashesNeedingFetch returns referenced hashes not yet in blobs, deduped`() =
        runTest {
            dao.upsert(msg("m1", attachmentHash = "H1"))
            dao.upsert(msg("m2", attachmentHash = "H2")) // H2 is held below → excluded
            dao.upsert(msg("m3", attachmentHash = "H1")) // duplicate reference → H1 appears once
            dao.upsert(msg("m4", attachmentHash = null)) // no attachment → excluded
            db.blobDao().insert(BlobEntity(hash = "H2", mime = "image/jpeg", bytes = ByteArray(0)))

            assertEquals(listOf("H1"), dao.hashesNeedingFetch())
        }

    @Test
    fun `pendingForRecipient returns only unsealed DMs, and clearPending removes them`() =
        runTest {
            dao.upsert(msg("p1", recipientId = "bob", pendingKey = true))
            dao.upsert(msg("p2", recipientId = "bob", pendingKey = true))
            dao.upsert(msg("sent", recipientId = "bob", pendingKey = false)) // already flooded → not pending
            dao.upsert(msg("other", recipientId = "carol", pendingKey = true)) // different recipient

            assertEquals(setOf("p1", "p2"), dao.pendingForRecipient("bob").map { it.id }.toSet())

            dao.clearPending("p1")
            assertEquals(setOf("p2"), dao.pendingForRecipient("bob").map { it.id }.toSet())
        }

    @Test
    fun `markReceived flips the delivery-ack flag and records the receipt's plane`() =
        runTest {
            dao.upsert(msg("m1", received = false))
            dao.markReceived("m1", DeliveryPlane.Nearby.code)
            val nearby = dao.observeAll().first().single { it.id == "m1" }
            assertTrue(nearby.received)
            assertEquals(DeliveryPlane.Nearby, nearby.receivedPlane)

            dao.upsert(msg("m2", received = false))
            dao.markReceived("m2", DeliveryPlane.Internet.code)
            val relayed = dao.observeAll().first().single { it.id == "m2" }
            assertTrue(relayed.received)
            assertEquals(DeliveryPlane.Internet, relayed.receivedPlane)
        }

    @Test
    fun `markReceived keeps the plane of the receipt that first flipped the tick`() =
        runTest {
            // A receipt is re-served routinely, and the duplicate can cross on the other plane. The mark
            // must keep describing the delivery that actually happened, in both directions.
            dao.upsert(msg("nearby-first", received = false))
            dao.markReceived("nearby-first", DeliveryPlane.Nearby.code)
            dao.markReceived("nearby-first", DeliveryPlane.Internet.code)
            assertEquals(
                DeliveryPlane.Nearby,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "nearby-first" }
                    .receivedPlane,
            )

            dao.upsert(msg("relay-first", received = false))
            dao.markReceived("relay-first", DeliveryPlane.Internet.code)
            dao.markReceived("relay-first", DeliveryPlane.Nearby.code)
            assertEquals(
                DeliveryPlane.Internet,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "relay-first" }
                    .receivedPlane,
            )
        }

    @Test
    fun `insertIfAbsent leaves an existing row untouched`() =
        runTest {
            // The inbound write: a re-served frame is the same signed bytes, so the first row for an id is
            // the only one that ever should be — its arrival plane, its tick, and what was added since.
            dao.upsert(msg("m1", received = true).copy(receivedVia = DeliveryPlane.LoRa.code, voiceDurationMs = 1_500))

            assertEquals(-1L, dao.insertIfAbsent(msg("m1", received = false)))

            val row = dao.observeAll().first().single { it.id == "m1" }
            assertTrue(row.received)
            assertEquals(DeliveryPlane.LoRa, row.receivedPlane)
            assertEquals(1_500, row.voiceDurationMs)
        }

    @Test
    fun `insertIfAbsent inserts a new row`() =
        runTest {
            assertTrue(dao.insertIfAbsent(msg("m2").copy(receivedVia = DeliveryPlane.LoRa.code)) != -1L)
            assertEquals(
                DeliveryPlane.LoRa,
                dao
                    .observeAll()
                    .first()
                    .single { it.id == "m2" }
                    .receivedPlane,
            )
        }

    @Test
    fun `recipientOf distinguishes a DM from a broadcast or absent message`() =
        runTest {
            dao.upsert(msg("dm", recipientId = "bob"))
            dao.upsert(msg("bc", recipientId = null))
            assertEquals("bob", dao.recipientOf("dm"))
            assertEquals(null, dao.recipientOf("bc"))
            assertEquals(null, dao.recipientOf("missing"))
        }

    @Test
    fun `deleteByConversation clears a whole thread`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1"))
            dao.upsert(msg("b", conversationId = "t1"))
            dao.upsert(msg("c", conversationId = "t2"))

            dao.deleteByConversation("t1")

            assertFalse(dao.exists("a"))
            assertTrue(dao.exists("c"))
        }

    @Test
    fun `countMineIn counts only the local user's messages in a thread`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t", sender = "me"))
            dao.upsert(msg("b", conversationId = "t", sender = "them"))
            dao.upsert(msg("c", conversationId = "other", sender = "me"))
            assertEquals(1, dao.countMineIn("t", "me"))
            assertEquals(0, dao.countMineIn("empty", "me"))
        }

    @Test
    fun `conversationsIAuthoredIn returns distinct threads the user posted in`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1", sender = "me"))
            dao.upsert(msg("b", conversationId = "t1", sender = "me"))
            dao.upsert(msg("c", conversationId = "t2", sender = "me"))
            dao.upsert(msg("d", conversationId = "t3", sender = "them"))
            assertEquals(setOf("t1", "t2"), dao.conversationsIAuthoredIn("me").toSet())
        }

    @Test
    fun `distinctConversations returns every thread id once, regardless of sender`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t1", sender = "me"))
            dao.upsert(msg("b", conversationId = "t1", sender = "them")) // same thread, different sender
            dao.upsert(msg("c", conversationId = "t2", sender = "them"))
            dao.upsert(msg("d", conversationId = Conversations.NEARBY, sender = "them"))
            assertEquals(setOf("t1", "t2", Conversations.NEARBY), dao.distinctConversations().toSet())
        }

    @Test
    fun `deleteOldestInConversation keeps only the newest N by sentAt`() =
        runTest {
            (1..5).forEach { dao.upsert(msg("m$it", conversationId = "t", sentAt = it.toLong())) }
            dao.deleteOldestInConversation("t", keep = 2)
            assertEquals(
                setOf("m4", "m5"),
                dao
                    .observeForConversation("t")
                    .first()
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun `deleteOlderThan drops messages before the cutoff in that thread only`() =
        runTest {
            dao.upsert(msg("old", conversationId = "t", sentAt = 10L))
            dao.upsert(msg("new", conversationId = "t", sentAt = 100L))
            dao.upsert(msg("other", conversationId = "u", sentAt = 1L))
            dao.deleteOlderThan("t", cutoff = 50L)
            assertFalse(dao.exists("old"))
            assertTrue(dao.exists("new"))
            assertTrue(dao.exists("other")) // a different thread is untouched
        }

    @Test
    fun `conversationActivity reports per-thread count and newest sentAt`() =
        runTest {
            dao.upsert(msg("a", conversationId = "t", sentAt = 5L))
            dao.upsert(msg("b", conversationId = "t", sentAt = 9L))
            dao.upsert(msg("c", conversationId = "u", sentAt = 3L))
            val byId = dao.conversationActivity().associateBy { it.conversationId }
            assertEquals(2, byId["t"]!!.count)
            assertEquals(9L, byId["t"]!!.lastSentAt)
            assertEquals(1, byId["u"]!!.count)
        }

    @Suppress("LongParameterList") // a test data builder — optional params with defaults, not a real API surface
    @Test
    fun `observeById follows one message and goes null once it is deleted`() =
        runTest {
            dao.upsert(msg("m1", sender = "sam", sentAt = 7L))
            dao.upsert(msg("m2"))

            val loaded = dao.observeById("m1").first()
            assertEquals("sam", loaded?.senderId)
            assertEquals(7L, loaded?.sentAt)

            dao.deleteById("m1")
            assertNull(dao.observeById("m1").first())
        }

    private fun msg(
        id: String,
        recipientId: String? = null,
        conversationId: String = Conversations.NEARBY,
        attachmentHash: String? = null,
        received: Boolean = false,
        pendingKey: Boolean = false,
        sender: String = "s",
        sentAt: Long = 1L,
    ) = MessageEntity(
        id = id,
        senderId = sender,
        recipientId = recipientId,
        conversationId = conversationId,
        body = "",
        sentAt = sentAt,
        received = received,
        attachmentHash = attachmentHash,
        pendingKey = pendingKey,
    )
}
