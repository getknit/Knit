package app.getknit.knit.contacts

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The import rules on plain JVM: a card pins + accepts + registers the intro but never verifies; a
 * differing pinned key is refused; our own card and a stranger's junk are told apart; relay hints are
 * surfaced, never applied. Cards are minted with real keys so the codec's checks run for real.
 */
class ContactImporterTest {
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)

    private class Party(
        seed: Int,
    ) {
        val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(ByteArray(32) { ((it * 7 + seed) and 0xFF).toByte() })
        val bundle =
            PublicKeyBundle.fromRaw(
                signing.publicKey,
                X25519.publicFromPrivate(ByteArray(32) { ((it * 3 + seed) and 0xFF).toByte() }),
            )!!
        val nodeId = NodeId.fromPublicKeyBundle(bundle.encoded)
        private val signer = Ed25519Sign(signing.privateKey)

        fun card(
            name: String = "Bob",
            spools: List<String> = emptyList(),
        ) = ContactCard.parse(ContactCard.encode(bundle, name, spools, issuedAt = 1L) { signer.sign(it) }) as ContactCard.Parsed.Card
    }

    private val me = Party(1)
    private val bob = Party(2)

    private val accepted = MutableStateFlow(emptySet<String>())
    private val blocked = MutableStateFlow(emptySet<String>())
    private val spoolEnabled = MutableStateFlow(true)
    private val spoolUrls = MutableStateFlow(setOf("wss://mine.example/spool/v1"))

    @Before
    fun setUp() {
        TinkInit.ensure()
        coEvery { identity.nodeId() } returns me.nodeId
        every { identity.publicKeyBundle() } returns me.bundle.encoded
        every { settings.acceptedConversations } returns accepted
        every { settings.blockedNodeIds } returns blocked
        every { settings.spoolEnabled } returns spoolEnabled
        every { settings.spoolUrls } returns spoolUrls
        coEvery { peers.find(any()) } returns null
    }

    private fun importer(internetPlane: Boolean = true) = ContactImporter(peers, settings, identity, mesh, internetPlane)

    @Test
    fun aNewCardIsPreviewedThenPinnedAcceptedAndIntroducedButNeverVerified() =
        runTest {
            val ready = importer().preview(bob.card()) as ContactImporter.Preview.Ready
            assertEquals(bob.nodeId, ready.nodeId)
            assertEquals("Bob", ready.displayName)
            assertFalse(ready.alreadyContact)
            assertFalse(ready.relaysOff)
            assertEquals(8, ready.safetyNumber.split(" ").size)

            importer().import(ready)

            coVerify {
                peers.upsert(
                    match {
                        it.nodeId == bob.nodeId && it.pubKey == bob.bundle.encoded && it.name == "Bob" && !it.verified &&
                            it.updatedAt == 0L
                    },
                )
            }
            coVerify { settings.accept(bob.nodeId) }
            coVerify { mesh.importContact(bob.nodeId) }
        }

    @Test
    fun aStoredNameAndAnExistingVerificationSurviveTheImport() =
        runTest {
            coEvery { peers.find(bob.nodeId) } returns
                PeerEntity(bob.nodeId, name = "Robert", pubKey = bob.bundle.encoded, verified = true, updatedAt = 9L)
            val ready = importer().preview(bob.card(name = "Bob")) as ContactImporter.Preview.Ready
            assertEquals("Robert", ready.displayName)
            assertTrue(ready.alreadyContact)
            importer().import(ready)
            coVerify { peers.upsert(match { it.name == "Robert" && it.verified && it.updatedAt == 9L }) }
        }

    @Test
    fun aDifferentPinnedKeyIsRefused() =
        runTest {
            coEvery { peers.find(bob.nodeId) } returns PeerEntity(bob.nodeId, pubKey = me.bundle.encoded)
            assertTrue(importer().preview(bob.card()) is ContactImporter.Preview.Mismatch)
        }

    @Test
    fun ourOwnCardAndJunkAreToldApart() =
        runTest {
            assertEquals(ContactImporter.Preview.Self, importer().preview(me.card()))
            val invalid = importer().preview(ContactCard.parse("nonsense")) as ContactImporter.Preview.Invalid
            assertEquals(ContactCard.Reason.NOT_A_CARD, invalid.reason)
        }

    @Test
    fun relayHintsAreSurfacedNeverApplied() =
        runTest {
            val card = bob.card(spools = listOf("wss://theirs.example/spool/v1", "wss://mine.example/spool/v1"))
            val ready = importer().preview(card) as ContactImporter.Preview.Ready
            assertEquals(listOf("wss://theirs.example/spool/v1"), ready.unknownRelays)
            importer().import(ready)
            coVerify(exactly = 0) { settings.addSpoolUrl(any()) }

            spoolEnabled.value = false
            val off = importer().preview(card) as ContactImporter.Preview.Ready
            assertTrue(off.relaysOff)
            assertEquals(emptyList<String>(), off.unknownRelays)
            assertTrue((importer(internetPlane = false).preview(card) as ContactImporter.Preview.Ready).relaysOff)
        }

    @Test
    fun aBlockedPeerIsFlaggedAndUnblockedOnlyWhenAsked() =
        runTest {
            blocked.value = setOf(bob.nodeId)
            val ready = importer().preview(bob.card()) as ContactImporter.Preview.Ready
            assertTrue(ready.blocked)
            importer().import(ready, unblock = false)
            coVerify(exactly = 0) { settings.unblock(any(), any()) }
            importer().import(ready, unblock = true)
            coVerify { settings.unblock(bob.nodeId, null) }
        }
}
