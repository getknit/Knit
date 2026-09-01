package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a board set up for Knit calls itself, what a restore puts back (ADR 049), and whether it tells the
 * mesh nobody reads it (ADR 2026-09.emd7).
 */
class BoardNameTest {
    /** Firmware new enough for the unmonitored mark, and the release just before the plumbing landed. */
    private val new = "2.6.9.f223b8a"
    private val old = "2.6.8.ef9d0d7"

    @Test
    fun aKnitBoardIsNamedForKnitAndForItself() {
        assertEquals(BoardOwner("Knit abcd", "Knit", unmessagable = true), BoardName.forNode(0xABCDu, new))
        // Only the low two bytes count — the same suffix the firmware builds out of the last two MAC bytes.
        assertEquals(BoardOwner("Knit abcd", "Knit", unmessagable = true), BoardName.forNode(0x1234ABCDu, new))
    }

    @Test
    fun aKnitBoardTellsTheMeshNobodyReadsIt() {
        // Knit keeps only PRIVATE_APP off the air, so a stranger's Meshtastic DM is ACKed and then dropped.
        assertTrue(BoardName.forNode(0xABCDu, new).unmessagable)
        // ...but a stock board, and so a restored one, accepts messages like any other node.
        assertFalse(BoardName.stock(0xABCDu).unmessagable)
    }

    @Test
    fun theMarkIsLeftOffFirmwareThatWouldOnlyDropIt() {
        assertFalse("2.6.8 drops field 9 as an unknown one", BoardName.forNode(0xABCDu, old).unmessagable)
        assertFalse(
            "and would then never report it back, so the setup would look forever unfinished",
            BoardName.honoursUnmessagable(old),
        )
        assertTrue(BoardName.honoursUnmessagable(new))
    }

    @Test
    fun theMarkNeedsTheWholeVersionBeforeItIsWritten() {
        // Writing changes somebody's hardware, so anything this cannot read counts as too old — the
        // opposite of the airtime governor's reading of the same string, where over-charging is the safe way.
        assertFalse(BoardName.honoursUnmessagable(null))
        assertFalse(BoardName.honoursUnmessagable(""))
        assertFalse(BoardName.honoursUnmessagable("unknown"))
        assertFalse(
            "a two-part version says nothing about the patch the floor sits on",
            BoardName.honoursUnmessagable("2.6"),
        )
    }

    @Test
    fun theMarkFollowsTheOrdinaryVersionOrder() {
        assertFalse(BoardName.honoursUnmessagable("1.9.9"))
        assertFalse(BoardName.honoursUnmessagable("2.5.20.4c97351"))
        assertFalse(BoardName.honoursUnmessagable("2.6.0"))
        assertTrue(BoardName.honoursUnmessagable("2.6.10.9ce4455"))
        assertTrue(BoardName.honoursUnmessagable("2.7.0.705515a"))
        assertTrue(BoardName.honoursUnmessagable("3.0.0"))
    }

    @Test
    fun theShortNameIsExactlyMeshtasticsFourCharacterBudget() {
        assertEquals(4, BoardName.forNode(0xABCDu, new).shortName.length)
    }

    @Test
    fun theLongNameFitsTheFirmwareField() {
        // `User.long_name` is char[40] — 39 characters plus the terminator.
        assertTrue(BoardName.forNode(0xFFFFFFFFu, new).longName.length <= 39)
        assertTrue(BoardName.stock(0xFFFFFFFFu).longName.length <= 39)
    }

    @Test
    fun theStockNameIsWhatTheFirmwareWouldHaveChosen() {
        assertEquals(BoardOwner("Meshtastic abcd", "abcd"), BoardName.stock(0xABCDu))
    }

    @Test
    fun theSuffixIsFourLowercaseHexDigitsZeroPadded() {
        assertEquals("000f", BoardName.suffix(0x0Fu))
        assertEquals("ffff", BoardName.suffix(0xFFFFu))
        assertEquals("0000", BoardName.suffix(0x10000u))
    }

    @Test
    fun twoBoardsInOnePocketAreToldApart() {
        // The whole reason the name is not a bare "Knit": a node list of identical names is no help.
        assertTrue(BoardName.forNode(0xABCDu, new).longName != BoardName.forNode(0xABCEu, new).longName)
    }
}
