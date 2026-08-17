package app.getknit.knit.data.message

/**
 * How a delivery receipt reached this device — the plane recorded with the ✓✓ tick
 * ([MessageEntity.receivedVia]), so the UI can say a message got there over the Internet rather than a
 * nearby radio.
 *
 * Stored as [code], not as the enum name: an unknown code (a row written by a newer build, or a value
 * retired later) reads back as [Unknown] via [fromCode] instead of throwing, and it matches how the rest
 * of [MessageEntity] keeps its small closed sets (`moderation`, `kind`). Codes are frozen — append new
 * planes, never renumber.
 *
 * [Bluetooth] and [WifiAware] are the finer split of [Nearby], reserved so attributing a delivery to a
 * specific radio stays a pure addition. **Nothing writes them yet:** the inbound path can tell the
 * Internet plane from a radio (a spool-tagged source), but not one radio from the other — that needs
 * `MeshTransport.InboundFrame` to carry the child transport's `TransportKind`, which
 * `CompositeMeshTransport` already knows per child. Until then every radio delivery is [Nearby], and a
 * reader must treat all three as "arrived nearby".
 */
@Suppress("MagicNumber") // the literals ARE the frozen stored codes; naming each one would only restate it
enum class DeliveryPlane(
    val code: Int,
) {
    /** No plane recorded — a row acked by a build older than the column, or a code we don't know. */
    Unknown(0),

    /** A radio (Wi-Fi Aware or Bluetooth LE — which one isn't recorded). */
    Nearby(1),

    /** The Internet plane: the receipt was pulled off a spool (`docs/SPOOL_PROTOCOL.md`). */
    Internet(2),

    /** Reserved: a Bluetooth LE delivery, once inbound frames carry their radio. */
    Bluetooth(3),

    /** Reserved: a Wi-Fi Aware delivery, once inbound frames carry their radio. */
    WifiAware(4),
    ;

    companion object {
        /** The plane for a stored [code], falling back to [Unknown] rather than throwing on an unknown one. */
        fun fromCode(code: Int): DeliveryPlane = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
