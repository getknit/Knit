package app.getknit.knit.ui.chat

import androidx.annotation.StringRes
import app.getknit.knit.R
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity

/**
 * How far one of *our* messages has got, folded out of the three independent columns that record it:
 * [MessageEntity.pendingKey] (saved locally, never sealed — we don't hold the recipient's key yet),
 * [MessageEntity.received] (a delivery receipt came back), and nothing at all in between.
 *
 * A received message has no status of its own — delivery isn't ours to report — so [deliveryLabel]
 * takes `mine` and describes *arrival* instead for the other direction.
 */
enum class DeliveryStatus {
    /** Written locally but never sent: the recipient's public key hasn't reached us yet. */
    Pending,

    /** Flooded, no receipt back yet (or a broadcast/group send, which never gets one). */
    Sent,

    /** A delivery receipt came back. */
    Delivered,

    ;

    companion object {
        /** The status the stored row implies. Order matters: `pendingKey` outranks the (always false) tick. */
        fun of(message: MessageEntity): DeliveryStatus =
            when {
                message.pendingKey -> Pending
                message.received -> Delivered
                else -> Sent
            }
    }
}

/**
 * The user-facing line for a message's delivery [status] on [plane]. The single definition shared by the
 * chat bubble's tick (an accessibility description — the tick itself is icon-only) and the message-details
 * screen (where it is visible text), so the two can't drift apart.
 *
 * The plane only qualifies a delivery that actually happened: a [DeliveryStatus.Sent] message has no
 * receipt yet, so there is no plane to name. For an inbound message the frame's own arrival plane is the
 * evidence ([MessageEntity.receivedVia]), and all three radio values read as "nearby" — see [DeliveryPlane].
 */
@StringRes
fun deliveryLabel(
    status: DeliveryStatus,
    plane: DeliveryPlane,
    mine: Boolean,
): Int =
    when {
        !mine && plane == DeliveryPlane.Internet -> R.string.chat_status_arrived_internet
        !mine -> R.string.chat_status_arrived_nearby
        status == DeliveryStatus.Pending -> R.string.chat_status_pending_key
        status == DeliveryStatus.Delivered && plane == DeliveryPlane.Internet -> R.string.chat_status_delivered_internet
        status == DeliveryStatus.Delivered -> R.string.chat_status_delivered
        else -> R.string.chat_status_sent
    }
