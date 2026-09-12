package app.getknit.knit

import app.getknit.knit.data.message.GROUP_FACES_MAX
import app.getknit.knit.data.message.GroupFace
import app.getknit.knit.data.message.groupFaceIds
import app.getknit.knit.data.message.groupFaces
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupFacesTest {
    @Test
    fun excludesSelfAndOrdersByNodeIdNotRoster() {
        // Roster order is whatever the creator typed; the cells must not depend on it.
        assertEquals(listOf("a", "m", "z"), groupFaceIds(listOf("me", "z", "a", "m"), selfId = "me"))
    }

    @Test
    fun keepsTheFirstFourByNodeIdAndDropsTheRestSilently() {
        val roster = listOf("me", "f", "b", "e", "a", "d", "c")
        val faces = groupFaceIds(roster, selfId = "me")
        assertEquals(GROUP_FACES_MAX, faces.size)
        assertEquals(listOf("a", "b", "c", "d"), faces)
    }

    @Test
    fun fewerThanTwoOthersIsNoClusterAtAll() {
        // A 2-member group would be one face — indistinguishable from that person's DM — so it gets the glyph.
        assertEquals(emptyList<String>(), groupFaceIds(listOf("me", "x"), selfId = "me"))
        assertEquals(emptyList<String>(), groupFaceIds(listOf("me"), selfId = "me"))
        assertEquals(emptyList<String>(), groupFaceIds(emptyList(), selfId = "me"))
    }

    @Test
    fun aNullSelfExcludesNobody() {
        // Before the identity has loaded, every member is "other"; the cluster is still deterministic.
        assertEquals(listOf("a", "me"), groupFaceIds(listOf("me", "a"), selfId = null))
    }

    @Test
    fun duplicateIdsCollapseToOneFace() {
        assertEquals(listOf("a", "b"), groupFaceIds(listOf("me", "b", "a", "b", "a"), selfId = "me"))
    }

    @Test
    fun facesResolveTheLabelAndHashThroughTheDirectory() {
        val directory = directoryOf(listOf(peer("a", name = "Ada", avatarHash = "h-ada"), peer("b", name = "Bea")))
        val faces = groupFaces(listOf("me", "b", "a"), selfId = "me", directory = directory)
        assertEquals(
            listOf(GroupFace("a", "Ada", "h-ada"), GroupFace("b", "Bea", null)),
            faces,
        )
    }

    @Test
    fun aMemberThePeerTableNeverSawStillGetsAFace() {
        // The label falls back to the alias the way every other surface names an unknown id; no photo.
        val directory = directoryOf(listOf(peer("a", name = "Ada")))
        val faces = groupFaces(listOf("me", "a", "stranger1"), selfId = "me", directory = directory)
        assertEquals(listOf("a", "stranger1"), faces.map { it.nodeId })
        assertEquals(directory.label("stranger1").text, faces[1].name)
        assertNull(faces[1].avatarHash)
    }

    @Test
    fun sameNameMembersKeepTheirDisambiguatedLabels() {
        // Two Sams: the cell initial is the same "S", but the name carried is the ADR 058 label, not the bare name.
        val directory = directoryOf(listOf(peer("sam-1", name = "Sam"), peer("sam-2", name = "Sam")))
        val faces = groupFaces(listOf("me", "sam-2", "sam-1"), selfId = "me", directory = directory)
        assertEquals(listOf(directory.label("sam-1").text, directory.label("sam-2").text), faces.map { it.name })
    }
}
