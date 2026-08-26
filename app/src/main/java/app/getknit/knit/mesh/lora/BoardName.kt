package app.getknit.knit.mesh.lora

/**
 * A Meshtastic node's user-visible identity — the pair of names its own screen, every other radio's node
 * list and the Meshtastic app all show. Recorded before a setup rewrites it so a restore can put the
 * user's own name back rather than guess.
 */
internal data class BoardOwner(
    val longName: String,
    val shortName: String,
)

/**
 * What a board set up for Knit calls itself (ADR 049).
 *
 * A stock board names itself `Meshtastic ab12` after the low two bytes of its node number; Knit keeps that
 * shape and swaps the prefix, so a board on the Knit channel is recognisable at a glance from any other
 * radio's node list without two boards in one pocket becoming indistinguishable. The short name — the
 * 4-character tag the small screens actually have room for — is exactly `Knit`, which is the whole of
 * Meshtastic's `short_name` budget (`char[5]`, one byte of it the terminator).
 *
 * Deliberately **not** the user's display name: a `NodeInfo` is cleartext on the public frequency, and the
 * plane's standing metadata cost (`context/lora-bridge.md`) is already the most this should leak.
 *
 * Pure policy over strings, so a test can read both directions.
 */
internal object BoardName {
    /** The long-name prefix; the rest is [suffix], as the firmware's own default does it. */
    const val PREFIX = "Knit"

    /** The short name, in full: four characters is the entire `short_name` field, and `Knit` is four. */
    const val SHORT = "Knit"

    /** The firmware's own default prefix, for a restore with no recorded name to put back. */
    const val STOCK_PREFIX = "Meshtastic"

    /** What Knit renames the board with node number [nodeNum] to. */
    fun forNode(nodeNum: UInt): BoardOwner = BoardOwner(longName = "$PREFIX ${suffix(nodeNum)}", shortName = SHORT)

    /**
     * The name the firmware would have given this board itself (`NodeDB` builds both out of the last two
     * MAC bytes, which are the low half of [nodeNum]) — what a restore writes when the setup that renamed
     * the board recorded nothing, so an un-recorded board still ends up stock rather than left saying Knit.
     */
    fun stock(nodeNum: UInt): BoardOwner = BoardOwner(longName = "$STOCK_PREFIX ${suffix(nodeNum)}", shortName = suffix(nodeNum))

    /** The four lowercase hex digits both names end in: the low two bytes of [nodeNum]. */
    fun suffix(nodeNum: UInt): String = (nodeNum and SUFFIX_MASK).toInt().toString(HEX_RADIX).padStart(SUFFIX_CHARS, '0')

    private const val SUFFIX_MASK = 0xFFFFu
    private const val SUFFIX_CHARS = 4
    private const val HEX_RADIX = 16
}
