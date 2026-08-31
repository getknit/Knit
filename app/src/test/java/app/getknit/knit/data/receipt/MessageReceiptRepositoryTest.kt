package app.getknit.knit.data.receipt

import androidx.room3.withWriteTransaction
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the **real** [MessageReceiptDao] SQL, plus the one thing about [MessageReceiptRepository.record]
 * that cannot be reasoned about from the call sites: the sealed-receipt path already runs inside the ctl
 * commit's `db.withWriteTransaction`, so `record` opens a **nested** transaction on SQLCipher's single
 * connection. Room reuses the in-flight transaction rather than starting a second one — but the failure
 * mode if that were wrong is a silent coroutine deadlock (no thread holds anything, nothing throws), so it
 * is pinned here rather than assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReceiptRepositoryTest : RoomDbTest() {
    private val dao get() = db.messageReceiptDao()
    private val repo get() = MessageReceiptRepository(dao, MessageRepository(db.messageDao()), db)

    private suspend fun received(id: String) =
        db
            .messageDao()
            .observeById(id)
            .first()!!
            .received

    private suspend fun seed(id: String) = db.messageDao().upsert(MessageEntity(id = id, senderId = "me", body = "", sentAt = 1L))

    @Test
    fun `record flips the tick and names the acker in one transaction`() =
        runTest {
            seed("m1")

            repo.record("m1", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)

            assertTrue(received("m1"))
            val row = dao.observeForMessage("m1").first().single()
            assertEquals("sam", row.ackerNodeId)
            assertEquals(10L, row.notedAt)
            assertEquals(DeliveryPlane.Nearby.code, row.via)
        }

    @Test
    fun `an unattributable receipt still ticks and simply names nobody`() =
        runTest {
            // The tick's "≥1 recipient received it" rule is a wire semantic and must not inherit the row's
            // roster gate — a non-member's group ack ticks exactly as it always has.
            seed("m2")

            repo.record("m2", acker = null, via = DeliveryPlane.Nearby, at = 10L)

            assertTrue(received("m2"))
            assertTrue(dao.observeForMessage("m2").first().isEmpty())
        }

    @Test
    fun `a re-served receipt keeps the first crossing's time and plane`() =
        runTest {
            seed("m3")

            repo.record("m3", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)
            repo.record("m3", acker = "sam", via = DeliveryPlane.Internet, at = 999L)

            val row = dao.observeForMessage("m3").first().single()
            assertEquals(10L, row.notedAt)
            assertEquals(DeliveryPlane.Nearby.code, row.via)
        }

    @Test
    fun `record nested inside an outer transaction commits instead of deadlocking`() =
        runTest {
            // The shape the sealed CTL_RECEIPT path takes in production: the ctl commit holds the
            // transaction, and record opens its own inside it.
            seed("m4")

            db.withWriteTransaction {
                repo.record("m4", acker = "sam", via = DeliveryPlane.Nearby, at = 10L)
            }

            assertTrue(received("m4"))
            assertEquals(
                "sam",
                dao
                    .observeForMessage("m4")
                    .first()
                    .single()
                    .ackerNodeId,
            )
        }

    @Test
    fun `deleteOrphans reclaims rows whose message is gone and spares the rest`() =
        runTest {
            // No FK cascade, so a deleted conversation, a group leave, and either retention trim all leave
            // rows behind. Unlike reactions there is no age floor: a receipt is only ever written for a
            // message we already hold, so an orphan can only mean the message has since gone.
            seed("m_live")
            dao.insertIfAbsent(MessageReceiptEntity("m_live", "sam", 1L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m_gone", "sam", 1L, DeliveryPlane.Nearby.code))

            dao.deleteOrphans()

            assertTrue(dao.observeForMessage("m_live").first().isNotEmpty())
            assertTrue(dao.observeForMessage("m_gone").first().isEmpty())
        }

    @Test
    fun `deleteForMessage drops one message's rows and leaves another's alone`() =
        runTest {
            seed("m5")
            seed("m6")
            dao.insertIfAbsent(MessageReceiptEntity("m5", "sam", 1L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m6", "sam", 1L, DeliveryPlane.Nearby.code))

            repo.deleteForMessage("m5")

            assertTrue(dao.observeForMessage("m5").first().isEmpty())
            assertFalse(dao.observeForMessage("m6").first().isEmpty())
        }

    @Test
    fun `observeForMessage returns each acker oldest first`() =
        runTest {
            seed("m7")
            dao.insertIfAbsent(MessageReceiptEntity("m7", "theo", 30L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m7", "sam", 10L, DeliveryPlane.Nearby.code))
            dao.insertIfAbsent(MessageReceiptEntity("m7", "priya", 20L, DeliveryPlane.Nearby.code))

            assertEquals(listOf("sam", "priya", "theo"), dao.observeForMessage("m7").first().map { it.ackerNodeId })
        }
}
