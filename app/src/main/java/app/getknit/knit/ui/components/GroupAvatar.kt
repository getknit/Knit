package app.getknit.knit.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.data.message.GROUP_FACES_MAX
import app.getknit.knit.data.message.GROUP_FACES_MIN
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.ui.icons.KnitIcons
import app.getknit.knit.ui.image.BlobImage
import app.getknit.knit.ui.preview.KnitPreview
import app.getknit.knit.ui.theme.knitColors
import app.getknit.knit.ui.theme.rememberPressScale
import app.getknit.knit.ui.util.clusterCells
import coil3.compose.AsyncImage
import kotlin.math.min

/**
 * A group's circular avatar, shared by the chat list, the chat header, the group-details screen, the
 * requests inbox and search. Renders the group's photo blob (content hash [photoHash], loaded from the
 * encrypted store via Coil) when present. Without one it is a **member cluster** (ADR 2026-09.zapp): the
 * group's other members fill the disc as cells — halves, a half and two quarters, or quadrants — each cell
 * that member's photo or their own tinted initial, the way [Avatar] would draw them alone. [faces] is
 * `groupFaces(...)`'s pick: self left out, ordered by node id, at most [GROUP_FACES_MAX]; the cells trust
 * that order and never re-sort, so the notification shade — which draws from the same `clusterCells` — shows
 * the same picture. Fewer than [GROUP_FACES_MIN] faces (a two-member group, an unknown roster) falls back to
 * a people glyph on a disc tinted by [groupId], the same node-keyed treatment a photo-less contact gets, so
 * the group never reads as "no identity". Cell seams are transparent: the surface behind shows through.
 *
 * When [onClick] is non-null the whole circle is tappable with a circular ripple and grows to the 48dp
 * minimum touch target (the visible circle stays [size]).
 *
 * Accessibility: pass [contentDescription] to give the avatar an accessible name (do this when
 * [onClick] is set, so the tappable target is announced), and [onClickLabel] to describe the action.
 * The cells themselves are decorative and add no semantics of their own.
 */
@Composable
fun GroupAvatar(
    photoHash: String?,
    groupId: String,
    faces: List<GroupFace>,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val tint = MaterialTheme.knitColors.avatarTint(groupId)
    // Show the group's photo when its blob is present; fall back otherwise. "Otherwise" includes a
    // *dangling* hash — one whose content-addressed blob is gone — since AsyncImage draws nothing on a
    // failed load. Keyed on [photoHash] so a fresh hash retries.
    var imageFailed by remember(photoHash) { mutableStateOf(false) }
    val showPhoto = photoHash != null && !imageFailed
    val showCluster = !showPhoto && faces.size >= GROUP_FACES_MIN
    // An avatar is a tap target with no container of its own, so a ripple alone is easy to miss on a
    // photo. Giving it a little under the finger is the confirmation the image can't provide.
    val interaction = remember { MutableInteractionSource() }
    // Only a tappable avatar has a press to react to; a decorative one never starts the collector.
    val press =
        if (onClick != null) {
            val scale = rememberPressScale(interaction)
            Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
        } else {
            Modifier
        }
    Box(
        modifier =
            modifier
                .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
                .then(press)
                .size(size)
                .clip(CircleShape)
                // The cluster paints its own cells and leaves the seams to whatever sits behind the disc.
                .then(if (showCluster) Modifier else Modifier.background(tint.container))
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            // See Avatar: the ripple takes the glyph's own colour rather than whatever
                            // content colour the surrounding row happens to carry.
                            indication = ripple(color = tint.onContainer),
                            onClickLabel = onClickLabel,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ).then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            showPhoto -> {
                AsyncImage(
                    model = BlobImage(photoHash),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { imageFailed = true },
                )
            }

            showCluster -> {
                MemberCluster(faces = faces, size = size, textStyle = textStyle)
            }

            else -> {
                Icon(
                    KnitIcons.Group,
                    contentDescription = null,
                    tint = tint.onContainer,
                    modifier = Modifier.size(size * GLYPH_FRACTION),
                )
            }
        }
    }
}

/**
 * The cells of a member cluster, placed straight from [clusterCells] in dp: the JVM-tested geometry *is*
 * the layout, with no measure policy of its own to drift from it. The seam is [CLUSTER_GAP] at every size,
 * handed to the geometry as a fraction of [size].
 */
@Composable
private fun MemberCluster(
    faces: List<GroupFace>,
    size: Dp,
    textStyle: TextStyle,
) {
    val shown = faces.take(GROUP_FACES_MAX)
    val cells = remember(shown.size, size) { clusterCells(shown.size, gap = CLUSTER_GAP / size) }
    Box(Modifier.fillMaxSize()) {
        shown.forEachIndexed { i, face ->
            val cell = cells[i]
            FaceCell(
                face = face,
                // The initial scales on the cell's short side, so a half-height quarter and a full-height
                // half carry letters of comparable weight.
                cellSize = size * min(cell.width, cell.height),
                textStyle = textStyle,
                modifier =
                    Modifier
                        .offset(x = size * cell.left, y = size * cell.top)
                        .size(width = size * cell.width, height = size * cell.height),
            )
        }
    }
}

/**
 * One member's cell: their photo centre-cropped to the cell (Coil clips to its own bounds), or their
 * initial on their own [app.getknit.knit.ui.theme.AvatarTint] — the same colour they wear alone in [Avatar].
 */
@Composable
private fun FaceCell(
    face: GroupFace,
    cellSize: Dp,
    textStyle: TextStyle,
    modifier: Modifier,
) {
    val tint = MaterialTheme.knitColors.avatarTint(face.nodeId)
    var imageFailed by remember(face.avatarHash) { mutableStateOf(false) }
    Box(modifier.background(tint.container), contentAlignment = Alignment.Center) {
        if (face.avatarHash != null && !imageFailed) {
            AsyncImage(
                model = BlobImage(face.avatarHash),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { imageFailed = true },
            )
        } else {
            AvatarInitial(name = face.name, size = cellSize, textStyle = textStyle, contentColor = tint.onContainer)
        }
    }
}

/** The seam between cells, the same at every avatar size; the shade's twin is `MessageNotifier.CLUSTER_GAP_PX`. */
private val CLUSTER_GAP = 1.5.dp

/** The people glyph's side as a fraction of the disc; `MessageNotifier.groupGlyphAvatar` matches it. */
private const val GLYPH_FRACTION = 0.6f

// Previews use the initial fallback (avatarHash = null); a real hash would render through Coil, which has
// no DB-backed blob bytes in a preview and so would only show a placeholder.

/** Two, three, four and five faces at the chat list's size — five proves the fifth is dropped, not counted. */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GroupAvatarClusterPreview() =
    KnitPreview {
        // Consecutive one-letter keys land in consecutive tint slots, as in AvatarPalettePreview.
        val faces = ('l'..'p').map { GroupFace(nodeId = it.toString(), name = it.uppercase(), avatarHash = null) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            for (n in 2..5) {
                GroupAvatar(photoHash = null, groupId = "group", faces = faces.take(n), size = 52.dp)
            }
        }
    }

/** Too few faces for a cluster: the glyph on the group's own tint, at the list's and the details screen's sizes. */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun GroupAvatarGlyphPreview() =
    KnitPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
            GroupAvatar(photoHash = null, groupId = "group", faces = emptyList(), size = 52.dp)
            GroupAvatar(
                photoHash = null,
                groupId = "group",
                faces = listOf(GroupFace(nodeId = "l", name = "L", avatarHash = null)),
                size = 96.dp,
            )
        }
    }

/** The details screen's size, where a cell is large enough to be a face in its own right. */
@Preview(showBackground = true)
@Composable
fun GroupAvatarLargePreview() =
    KnitPreview {
        val faces = listOf("Sam", "Priya", "Theo").mapIndexed { i, name -> GroupFace(nodeId = "m$i", name = name, avatarHash = null) }
        GroupAvatar(photoHash = null, groupId = "group", faces = faces, size = 96.dp)
    }
