package app.getknit.knit.ui.util

import androidx.compose.ui.geometry.Rect
import app.getknit.knit.data.message.GROUP_FACES_MAX
import app.getknit.knit.data.message.GROUP_FACES_MIN

/**
 * The cells of a group's member cluster, in fractions of the unit square, so the Compose avatar and the
 * notification shade's Canvas draw the same picture from the same numbers (ADR 2026-09.zapp). The outer
 * circle clip is the renderer's; cells run flush to the square's edge and let it round them.
 *
 * Order is fixed and is what `groupFaceIds`'s order maps onto — renderers place `faces[i]` in `cells[i]`:
 * - 2 → left half, right half
 * - 3 → left half, top-right quarter, bottom-right quarter
 * - 4 → top-left, top-right, bottom-left, bottom-right
 *
 * [gap] is the seam between cells as a fraction of the diameter; half of it is shaved off each side of every
 * interior edge, so the seam is exactly [gap] wide and the outer edges stay flush. It is a fraction here and
 * a length at the caller — `CLUSTER_GAP / size` in Compose, `CLUSTER_GAP_PX / AVATAR_PX` in the shade — because
 * a fixed fraction would draw a 2.9dp seam at 96dp and a 1.1dp one at 36dp, and a dp has no meaning in a
 * bitmap the shade scales itself.
 */
fun clusterCells(
    count: Int,
    gap: Float,
): List<Rect> {
    require(count in GROUP_FACES_MIN..GROUP_FACES_MAX) { "a cluster holds $GROUP_FACES_MIN..$GROUP_FACES_MAX faces, not $count" }
    require(gap in 0f..<HALF) { "gap must be a fraction in [0, ½), not $gap" }
    val h = gap / 2f
    val left = Rect(left = 0f, top = 0f, right = HALF - h, bottom = 1f)
    val right = Rect(left = HALF + h, top = 0f, right = 1f, bottom = 1f)
    return when (count) {
        GROUP_FACES_MIN -> {
            listOf(left, right)
        }

        GROUP_FACES_MAX -> {
            listOf(
                Rect(left = 0f, top = 0f, right = HALF - h, bottom = HALF - h),
                Rect(left = HALF + h, top = 0f, right = 1f, bottom = HALF - h),
                Rect(left = 0f, top = HALF + h, right = HALF - h, bottom = 1f),
                Rect(left = HALF + h, top = HALF + h, right = 1f, bottom = 1f),
            )
        }

        // Three: the left half whole, the right half split.
        else -> {
            listOf(
                left,
                Rect(left = HALF + h, top = 0f, right = 1f, bottom = HALF - h),
                Rect(left = HALF + h, top = HALF + h, right = 1f, bottom = 1f),
            )
        }
    }
}

/** The midline of the unit square: every seam sits on it. */
private const val HALF = 0.5f
