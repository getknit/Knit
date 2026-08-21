package app.getknit.knit.ui.chat

import app.getknit.knit.R
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one definition of "how far did it get", shared by the chat bubble's tick description and the
 * message-details screen's visible line. The rule worth pinning: the plane only qualifies a delivery that
 * actually happened — an un-acked send names no plane, however it was eventually going to travel.
 */
class DeliveryStatusTest {
    @Test
    fun `pendingKey outranks the tick`() {
        assertEquals(DeliveryStatus.Pending, DeliveryStatus.of(message(pendingKey = true)))
        assertEquals(DeliveryStatus.Sent, DeliveryStatus.of(message()))
        assertEquals(DeliveryStatus.Delivered, DeliveryStatus.of(message(received = true)))
    }

    @Test
    fun `our own message reports sent, delivered, or delivered over the Internet`() {
        assertEquals(
            R.string.chat_status_pending_key,
            deliveryLabel(DeliveryStatus.Pending, DeliveryPlane.Unknown, mine = true),
        )
        assertEquals(
            R.string.chat_status_sent,
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Unknown, mine = true),
        )
        assertEquals(
            R.string.chat_status_delivered,
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true),
        )
        assertEquals(
            R.string.chat_status_delivered_internet,
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `an un-acked send names no plane even when one is recorded`() {
        assertEquals(
            R.string.chat_status_sent,
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `a received message describes its own arrival, not our delivery`() {
        assertEquals(
            R.string.chat_status_arrived_internet,
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = false),
        )
        // All three radio values read as "nearby" — see DeliveryPlane.
        for (plane in listOf(DeliveryPlane.Nearby, DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware, DeliveryPlane.Unknown)) {
            assertEquals(
                R.string.chat_status_arrived_nearby,
                deliveryLabel(DeliveryStatus.Delivered, plane, mine = false),
            )
        }
    }

    private fun message(
        received: Boolean = false,
        pendingKey: Boolean = false,
    ) = MessageEntity(
        id = "m1",
        senderId = "me",
        body = "hi",
        sentAt = 0L,
        received = received,
        pendingKey = pendingKey,
    )
}
