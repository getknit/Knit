package app.getknit.knit.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Marks Knit draws itself, because no Material glyph says what they say.
 *
 * Each is declared like a Material icon — a 24 × 24 viewport, black paint that [androidx.compose.material3.Icon]
 * recolours to the content colour, built on first use and kept — so they sit beside `Icons.Filled.*` at a call
 * site without ceremony.
 */
object KnitIcons {
    private var directTransfer: ImageVector? = null

    /**
     * Direct file transfer: a page with a signal breaking off its corner.
     *
     * A paperclip would have been wrong here. It is the mark for an attachment — small, sealed, carried by the
     * mesh and kept in the blob store — and this is the opposite of that: the file leaves the phone whole, over
     * a link the two devices raise between themselves. The page says "a whole file", the waves say "over the
     * air", and nothing else in the app draws either.
     */
    val DirectTransfer: ImageVector
        get() =
            directTransfer ?: ImageVector
                .Builder(
                    name = "KnitIcons.DirectTransfer",
                    defaultWidth = ICON_SIZE.dp,
                    defaultHeight = ICON_SIZE.dp,
                    viewportWidth = ICON_SIZE,
                    viewportHeight = ICON_SIZE,
                ).apply {
                    // The page, bottom-left, its top-right corner cut away as a fold.
                    path(fill = SolidColor(Color.Black)) {
                        moveTo(2f, 8f)
                        lineTo(9f, 8f)
                        lineTo(12f, 11f)
                        lineTo(12f, 22f)
                        lineTo(2f, 22f)
                        close()
                    }
                    // Two quarter-arcs centred on that corner, so the signal leaves the page rather than
                    // floating beside it.
                    arc(radius = 4.5f)
                    arc(radius = 8.5f)
                }.build()
                .also { directTransfer = it }

    /** One wave: a quarter turn from straight up to straight right, around the page's cut corner. */
    private fun ImageVector.Builder.arc(radius: Float) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(CORNER_X, CORNER_Y - radius)
            arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, CORNER_X + radius, CORNER_Y)
        }
    }

    private const val ICON_SIZE = 24f
    private const val STROKE = 1.9f

    /** The page's cut corner, and so the point every wave turns around. */
    private const val CORNER_X = 12f
    private const val CORNER_Y = 11f
}
