// ktlint's filename rule wants a single-type file named after the type (GroupFace.kt); this file is
// deliberately named for its primary export, groupFaceIds (matching GroupFacesTest.kt), so the rule is
// suppressed here rather than misnaming the file after its row type.
@file:Suppress("ktlint:standard:filename")

package app.getknit.knit.data.message

import app.getknit.knit.data.PeerDirectory

/*
 * The members a photo-less group's avatar shows. Like `groupTitle`, this is each device's own view of the
 * roster — self left out — but unlike the title it is ordered by node id, not roster order, so two phones
 * that know the same group draw the same faces in the same cells. Pure/JVM-testable (no Android deps).
 * ADR 2026-09.zapp.
 */

/** Below this many others the cluster has nothing to say, and the caller draws the tinted glyph disc instead. */
const val GROUP_FACES_MIN = 2

/** The 2×2 grid's capacity. Members past it are dropped silently — there is no "+N" cell. */
const val GROUP_FACES_MAX = 4

/**
 * Node ids of the members a photo-less group's avatar shows: [memberIds] minus [selfId], de-duplicated,
 * sorted by node id, the first [GROUP_FACES_MAX] — or empty when fewer than [GROUP_FACES_MIN] others remain.
 *
 * The sort is the only ordering there is: every renderer places `faces[i]` in `clusterCells(n)[i]` and never
 * re-sorts, so the list and the notification shade agree cell for cell.
 */
fun groupFaceIds(
    memberIds: List<String>,
    selfId: String?,
): List<String> {
    val others =
        memberIds
            .asSequence()
            .filter { it != selfId }
            .distinct()
            .sorted()
            .toList()
    return if (others.size < GROUP_FACES_MIN) emptyList() else others.take(GROUP_FACES_MAX)
}

/**
 * [groupFaceIds] resolved through [directory]: the collision-aware label (`Name (Alias)` when another peer
 * shares the name, ADR 058) and the avatar hash. A member the peer table has never seen still gets a face —
 * the label falls back to its alias and the hash is null, so the cell draws a tinted initial.
 */
fun groupFaces(
    memberIds: List<String>,
    selfId: String?,
    directory: PeerDirectory,
): List<GroupFace> =
    groupFaceIds(memberIds, selfId).map { id ->
        GroupFace(nodeId = id, name = directory.label(id).text, avatarHash = directory.byNode[id]?.avatarHash)
    }

// Declared after the functions so the file's only type isn't its first declaration — that keeps the
// meaningful file name GroupFaces.kt instead of detekt's MatchingDeclarationName forcing GroupFace.kt.

/**
 * One cell of a group's member cluster as the UI draws it: the member's [nodeId] (the cell's tint key), the
 * [name] whose leading grapheme is the initial, and the [avatarHash] Coil loads when there is a photo. The
 * notification shade's twin carries bytes instead (`NotifFace`).
 */
data class GroupFace(
    val nodeId: String,
    val name: String,
    val avatarHash: String?,
)
