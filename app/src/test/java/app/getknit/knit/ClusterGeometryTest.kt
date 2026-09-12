package app.getknit.knit

import androidx.compose.ui.geometry.Rect
import app.getknit.knit.ui.util.clusterCells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterGeometryTest {
    private val counts = 2..4
    private val gaps = listOf(0f, 0.03f)

    @Test
    fun everyCellLiesInsideTheUnitSquare() {
        for (n in counts) {
            for (gap in gaps) {
                val cells = clusterCells(n, gap)
                assertEquals("$n faces", n, cells.size)
                for (c in cells) {
                    assertTrue("$c", c.left >= 0f && c.top >= 0f && c.right <= 1f && c.bottom <= 1f)
                    assertTrue("$c is empty", c.width > 0f && c.height > 0f)
                }
            }
        }
    }

    @Test
    fun cellsNeverOverlapAndNeighboursSitAGapApart() {
        for (n in counts) {
            for (gap in gaps) {
                val cells = clusterCells(n, gap)
                for (i in cells.indices) {
                    for (j in i + 1 until cells.size) {
                        val a = cells[i]
                        val b = cells[j]
                        assertFalse("$n/$gap: $a overlaps $b", a.overlaps(b))
                        // Two cells that share a seam are exactly `gap` apart on the axis that separates them.
                        val dx = maxOf(a.left - b.right, b.left - a.right)
                        val dy = maxOf(a.top - b.bottom, b.top - a.bottom)
                        assertEquals("$n/$gap: $a vs $b", gap, maxOf(dx, dy), EPSILON)
                    }
                }
            }
        }
    }

    @Test
    fun withNoGapTheCellsTileTheWholeSquare() {
        // Non-overlapping (above) and summing to one means they cover it.
        for (n in counts) {
            val area = clusterCells(n, gap = 0f).sumOf { (it.width * it.height).toDouble() }
            assertEquals("$n faces", 1.0, area, EPSILON.toDouble())
        }
    }

    @Test
    fun twoFacesAreMirrorHalves() {
        val (l, r) = clusterCells(2, GAP)
        assertSameRect(mirrorX(r), l)
        assertEquals(1f, l.height, EPSILON)
        assertEquals(1f, r.height, EPSILON)
    }

    @Test
    fun threeFacesAreAFullLeftHalfAndAMirroredRightColumn() {
        val (left, topRight, bottomRight) = clusterCells(3, GAP)
        assertEquals(0f, left.left, EPSILON)
        assertEquals(1f, left.height, EPSILON)
        assertSameRect(mirrorY(bottomRight), topRight)
        assertTrue("top-right is above bottom-right", topRight.bottom < bottomRight.top)
    }

    @Test
    fun fourFacesAreQuadrantsReadLikeText() {
        val cells = clusterCells(4, GAP)
        val (tl, tr, bl) = cells
        val br = cells[3]
        assertSameRect(mirrorX(tr), tl)
        assertSameRect(mirrorY(bl), tl)
        assertSameRect(mirrorX(mirrorY(br)), tl)
        assertTrue(tl.right < tr.left && tl.bottom < bl.top)
    }

    @Test
    fun aCountOutsideTheGridIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { clusterCells(1, GAP) }
        assertThrows(IllegalArgumentException::class.java) { clusterCells(5, GAP) }
        assertThrows(IllegalArgumentException::class.java) { clusterCells(2, gap = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { clusterCells(2, gap = -0.01f) }
    }

    /** Edge-by-edge within [EPSILON]: a mirrored `0.5f ± h` can land an ulp off its twin. */
    private fun assertSameRect(
        expected: Rect,
        actual: Rect,
    ) {
        assertEquals("left", expected.left, actual.left, EPSILON)
        assertEquals("top", expected.top, actual.top, EPSILON)
        assertEquals("right", expected.right, actual.right, EPSILON)
        assertEquals("bottom", expected.bottom, actual.bottom, EPSILON)
    }

    private fun mirrorX(r: Rect) = Rect(left = 1f - r.right, top = r.top, right = 1f - r.left, bottom = r.bottom)

    private fun mirrorY(r: Rect) = Rect(left = r.left, top = 1f - r.bottom, right = r.right, bottom = 1f - r.top)

    private companion object {
        const val GAP = 0.03f
        const val EPSILON = 1e-6f
    }
}
