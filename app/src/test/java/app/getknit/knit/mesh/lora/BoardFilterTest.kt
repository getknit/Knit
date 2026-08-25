package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's board heuristic and its escape hatch. */
class BoardFilterTest {
    private val board = BoardRef("AA:BB:CC:DD:EE:01", "Meshtastic_ee01")
    private val renamed = BoardRef("AA:BB:CC:DD:EE:02", "WALT_ee02")
    private val buds = BoardRef("11:22:33:44:55:66", "Pixel Buds", meshtastic = false)
    private val watch = BoardRef("11:22:33:44:55:77", "Galaxy Watch", meshtastic = false)
    private val all = listOf(board, buds, renamed, watch)

    @Test
    fun `the stock name and the renamed-board suffix look like boards, a headset does not`() {
        assertTrue(BoardFilter.looksLikeBoard("Meshtastic_ee01"))
        assertTrue(BoardFilter.looksLikeBoard("meshtastic_EE01"))
        assertTrue(BoardFilter.looksLikeBoard("Mesh"))
        // A custom short name: the firmware keeps the four MAC hex digits behind it.
        assertTrue(BoardFilter.looksLikeBoard("WALT_ee02"))
        assertFalse(BoardFilter.looksLikeBoard("Pixel Buds"))
        assertFalse(BoardFilter.looksLikeBoard("Galaxy Watch_4"))
        assertFalse(BoardFilter.looksLikeBoard("Keyboard_zz99"))
    }

    @Test
    fun `visible keeps the board-like devices and counts the rest as hidden`() {
        assertEquals(listOf(board, renamed), BoardFilter.visible(all, boundAddress = null, showAll = false))
        assertEquals(2, BoardFilter.hidden(all, boundAddress = null))
    }

    @Test
    fun `the bound device is always listed, however it is named`() {
        // The user renamed the board to something the heuristic misses: it must not vanish while selected.
        assertEquals(listOf(board, buds, renamed), BoardFilter.visible(all, boundAddress = buds.address, showAll = false))
        assertEquals(1, BoardFilter.hidden(all, boundAddress = buds.address))
    }

    @Test
    fun `show all lists everything in the directory's order`() {
        assertEquals(all, BoardFilter.visible(all, boundAddress = null, showAll = true))
    }
}
