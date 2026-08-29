package app.getknit.knit.data

import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.IdentitySource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drives [PeerRepository] against a real in-memory DB: [PeerRepository.sweepCap] with a tiny cap — the bound
 * that stops a Sybil profile flood from growing the (otherwise uncapped) `peers` table, while sparing
 * verified/known peers — and the name-collision directory that folds our own name into the universe.
 */
class PeerRepositoryTest : RoomDbTest() {
    private fun repo(
        myName: String = "",
        maxPeers: Int = 2_000,
    ): PeerRepository {
        val profile = mockk<InboundSettings>(relaxed = true)
        every { profile.displayName } returns flowOf(myName)
        val identity = mockk<IdentitySource>(relaxed = true)
        coEvery { identity.nodeId() } returns "me"
        return PeerRepository(db.peerDao(), profile, identity, maxPeers)
    }

    @Test
    fun `sweepCap evicts the oldest unverified strangers beyond the cap, sparing verified and protected`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "verified", verified = true, updatedAt = 1L))
            dao.upsert(PeerEntity(nodeId = "known", verified = false, updatedAt = 2L)) // protected (e.g. we messaged them)
            dao.upsert(PeerEntity(nodeId = "old", verified = false, updatedAt = 3L))
            dao.upsert(PeerEntity(nodeId = "new", verified = false, updatedAt = 4L))

            repo(maxPeers = 1).sweepCap(protected = setOf("known"))

            // Cappable pool = {old, new} (verified + protected spared); cap 1 → evict the 1 oldest = "old".
            assertNull(dao.findByNodeId("old"))
            assertNotNull(dao.findByNodeId("new"))
            assertNotNull(dao.findByNodeId("verified"))
            assertNotNull(dao.findByNodeId("known"))
        }

    @Test
    fun `observeDirectory folds our own name into the universe and labels same-named peers`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Alice"))
            dao.upsert(PeerEntity(nodeId = "b", name = "Bob"))

            val directory = repo(myName = "Alice").observeDirectory().first()

            assertEquals(Alias.aliasFor("a"), directory.label("a").discriminator) // collides with us
            assertNull(directory.label("b").discriminator)
            assertEquals("Alice (${Alias.aliasFor("me")})", directory.label("me").text) // our own name, from settings
            assertEquals(setOf("a", "b"), directory.byNode.keys)
        }

    @Test
    fun `labelIndex is the same universe read through the light projection`() =
        runTest {
            val dao = db.peerDao()
            dao.upsert(PeerEntity(nodeId = "a", name = "Sam", pubKey = "k1"))
            dao.upsert(PeerEntity(nodeId = "b", name = "sam", pubKey = "k2"))
            dao.upsert(PeerEntity(nodeId = "c", name = "Carol"))

            val labels = repo(myName = "Me").labelIndex()

            assertEquals("Sam (${Alias.aliasFor("a")})", labels.labelFor("a").text)
            assertEquals("sam (${Alias.aliasFor("b")})", labels.labelFor("b").text)
            assertEquals("Carol", labels.labelFor("c").text)
            assertEquals("Me", labels.labelFor("me").text)
            assertEquals(Alias.aliasFor("zz"), labels.labelFor("zz").text) // never pinned → alias, undiscriminated
        }
}
