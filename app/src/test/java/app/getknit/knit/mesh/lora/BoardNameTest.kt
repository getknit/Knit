package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a board set up for Knit calls itself, and what a restore puts back (ADR 049). */
class BoardNameTest {
    @Test
    fun aKnitBoardIsNamedForKnitAndForItself() {
        assertEquals(BoardOwner("Knit abcd", "Knit"), BoardName.forNode(0xABCDu))
        // Only the low two bytes count — the same suffix the firmware builds out of the last two MAC bytes.
        assertEquals(BoardOwner("Knit abcd", "Knit"), BoardName.forNode(0x1234ABCDu))
    }

    @Test
    fun theShortNameIsExactlyMeshtasticsFourCharacterBudget() {
        assertEquals(4, BoardName.forNode(0xABCDu).shortName.length)
    }

    @Test
    fun theLongNameFitsTheFirmwareField() {
        // `User.long_name` is char[40] — 39 characters plus the terminator.
        assertTrue(BoardName.forNode(0xFFFFFFFFu).longName.length <= 39)
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
        assertTrue(BoardName.forNode(0xABCDu).longName != BoardName.forNode(0xABCEu).longName)
    }
}
