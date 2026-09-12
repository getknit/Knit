package app.getknit.knit.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import app.getknit.knit.R

/**
 * Marks Knit draws itself, because no Material glyph says what they say.
 *
 * Each is a vector drawable rather than an [ImageVector] built in Kotlin, because these marks are needed in
 * two worlds: Compose reads them here, and the notification framework needs a real `R.drawable` for a
 * status-bar icon. One resource keeps those from drifting apart.
 */
object KnitIcons {
    /**
     * Direct file transfer: a page with a signal breaking off its corner.
     *
     * A paperclip would have been wrong here. It is the mark for an attachment — small, sealed, carried by the
     * mesh and kept in the blob store — and this is the opposite of that: the file leaves the phone whole, over
     * a link the two devices raise between themselves. The page says "a whole file", the waves say "over the
     * air", and nothing else in the app draws either.
     */
    val DirectTransfer: ImageVector
        @Composable
        get() = ImageVector.vectorResource(R.drawable.ic_direct_transfer)

    /**
     * A group with no photo and too few members to draw as a cluster: Material's own "group" glyph, but as
     * a resource rather than `Icons.Filled.Group`, because the notification shade paints the same fallback
     * disc onto a `Bitmap` and an [ImageVector] cannot be drawn there. ADR 2026-09.zapp.
     */
    val Group: ImageVector
        @Composable
        get() = ImageVector.vectorResource(R.drawable.ic_group_glyph)
}
