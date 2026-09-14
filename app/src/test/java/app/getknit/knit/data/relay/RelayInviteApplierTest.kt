package app.getknit.knit.data.relay

import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.CommonsRoots
import app.getknit.knit.mesh.spool.RelayInvite
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The one apply sequence on plain JVM: consent is recorded through the master switch's own write and
 * only when missing; the relay is stored, un-parked and dialled now; a room is joined, left alone when
 * already joined, or rotated with the old one left first; a relay is matched by its redacted URL so a tokened
 * invite replaces an untokened entry and never the reverse; a build with no commons store ignores the
 * room half.
 */
class RelayInviteApplierTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val commons = mockk<CommonsRepository>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)

    private val spoolEnabled = MutableStateFlow(false)
    private val spoolConsented = MutableStateFlow(false)
    private val spoolUrls = MutableStateFlow(emptySet<String>())
    private val parked = MutableStateFlow(emptySet<String>())
    private val roots = mutableListOf<CommonsRoots>()

    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun setUp() {
        every { settings.spoolEnabled } returns spoolEnabled
        every { settings.spoolConsented } returns spoolConsented
        every { settings.spoolUrls } returns spoolUrls
        every { settings.disabledSpoolUrls } returns parked
        coEvery { commons.roots() } answers { roots.toList() }
        coEvery { commons.find(any()) } answers
            {
                roots.firstOrNull { it.conversationId == firstArg() }?.let {
                    CommonsRoom(
                        it.conversationId,
                        it.spoolUrl,
                        it.secret,
                        "Home",
                    )
                }
            }
    }

    private fun applier(withCommons: Boolean = true) = RelayInviteApplier(settings, commons.takeIf { withCommons }, mesh, clock = { NOW })

    private fun invite(
        url: String = TOKENED,
        secret: ByteArray? = null,
        name: String? = null,
    ) = RelayInvite.Parsed.Invite(url, secret, name)

    @Test
    fun aFirstInviteRecordsConsentStoresTheRelayAndDialsNow() =
        runTest {
            val preview = applier().preview(invite())
            assertTrue(preview.consentNeeded)
            assertTrue(preview.planeOff)
            assertTrue(preview.private)
            assertEquals("home.example.org", preview.host)
            assertFalse(preview.alreadyAdded)
            assertNull(preview.room)
            assertFalse(preview.isNoOp)

            applier().apply(preview)
            coVerifyOrder {
                settings.acceptSpoolConsent()
                settings.addSpoolUrl(TOKENED)
                mesh.refreshRelays()
            }
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
            coVerify(exactly = 0) { settings.setSpoolUrlEnabled(any(), any()) }
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
        }

    @Test
    fun aConsentedButSwitchedOffPlaneIsTurnedOnWithoutASecondConsent() =
        runTest {
            spoolConsented.value = true
            val preview = applier().preview(invite())
            assertFalse(preview.consentNeeded)
            assertTrue(preview.planeOff)
            applier().apply(preview)
            coVerify(exactly = 0) { settings.acceptSpoolConsent() }
            coVerify { settings.setSpoolEnabled(true) }
        }

    @Test
    fun anAlreadyListedRelayIsANoOpUnlessParked() =
        runTest {
            spoolConsented.value = true
            spoolEnabled.value = true
            spoolUrls.value = setOf(TOKENED)
            val same = applier().preview(invite())
            assertTrue(same.alreadyAdded)
            assertTrue(same.isNoOp)
            applier().apply(same)
            coVerify(exactly = 0) { settings.setSpoolUrlEnabled(any(), any()) }
            coVerify { settings.addSpoolUrl(TOKENED) }

            parked.value = setOf(TOKENED)
            val parkedOne = applier().preview(invite())
            assertTrue(parkedOne.parked)
            assertFalse(parkedOne.isNoOp)
            applier().apply(parkedOne)
            coVerify { settings.setSpoolUrlEnabled(TOKENED, true) }
        }

    @Test
    fun aTokenedInviteReplacesAnUntokenedEntry() =
        runTest {
            spoolUrls.value = setOf(UNTOKENED)
            val up = applier().preview(invite(TOKENED))
            assertEquals(TOKENED, up.url)
            assertEquals(UNTOKENED, up.replaces)
            assertFalse(up.alreadyAdded)
            applier().apply(up)
            coVerifyOrder {
                settings.removeSpoolUrl(UNTOKENED)
                settings.addSpoolUrl(TOKENED)
            }
        }

    @Test
    fun anUntokenedInviteNeverDowngradesATokenedEntry() =
        runTest {
            spoolUrls.value = setOf(TOKENED)
            val down = applier().preview(invite(UNTOKENED))
            assertEquals(TOKENED, down.url)
            assertNull(down.replaces)
            assertTrue(down.alreadyAdded)
            assertTrue(down.private)
            applier().apply(down)
            coVerify(exactly = 0) { settings.removeSpoolUrl(any()) }
            coVerify(exactly = 0) { settings.addSpoolUrl(UNTOKENED) }
        }

    @Test
    fun aRoomJoinedUnderTheReplacedEntryMovesWithTheRelay() =
        runTest {
            spoolUrls.value = setOf(UNTOKENED)
            roots += CommonsRoots("c-1", UNTOKENED, secret)
            val preview = applier().preview(invite(TOKENED, secret))
            val room = preview.room!!
            assertTrue(room.alreadyJoined)
            assertFalse(room.replacesRoom)
            applier().apply(preview)
            coVerifyOrder {
                commons.join(TOKENED, secret, "Home", NOW)
                settings.removeSpoolUrl(UNTOKENED)
                settings.addSpoolUrl(TOKENED)
                mesh.refreshRelays()
            }
            // Re-bound once, with its stored name — never joined a second time under the link's empty one.
            coVerify(exactly = 1) { commons.join(any(), any(), any(), any()) }
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
        }

    @Test
    fun aRoomIsJoinedWithTheLinksNameThenTheLiveOne() =
        runTest {
            val named = applier().preview(invite(TOKENED, secret, "Ours"), liveName = "Theirs")
            assertEquals("Ours", named.room!!.name)
            applier().apply(named)
            coVerify { commons.join(TOKENED, secret, "Ours", NOW) }

            val live = applier().preview(invite(TOKENED, secret), liveName = "Theirs")
            assertEquals("Theirs", live.room!!.name)
            assertNull(applier().preview(invite(TOKENED, secret)).room!!.name)
        }

    @Test
    fun theSameRoomAgainIsANoOpUpsertAndADifferentOneIsARotation() =
        runTest {
            spoolConsented.value = true
            spoolEnabled.value = true
            spoolUrls.value = setOf(TOKENED)
            roots += CommonsRoots("c-1", TOKENED, secret)

            val same = applier().preview(invite(TOKENED, secret))
            val sameRoom = same.room!!
            assertTrue(sameRoom.alreadyJoined)
            assertFalse(sameRoom.replacesRoom)
            assertTrue(same.isNoOp)
            applier().apply(same)
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
            // Not re-joined: the upsert would overwrite the name the relay advertised at join time.
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }

            val rotated = applier().preview(invite(TOKENED, otherSecret))
            val rotatedRoom = rotated.room!!
            assertFalse(rotatedRoom.alreadyJoined)
            assertTrue(rotatedRoom.replacesRoom)
            assertFalse(rotated.isNoOp)
            applier().apply(rotated)
            coVerifyOrder {
                commons.leaveBoundTo(TOKENED)
                commons.join(TOKENED, otherSecret, null, NOW)
                mesh.refreshRelays()
            }
        }

    @Test
    fun aBuildWithNoCommonsStoreAppliesTheRelayAndIgnoresTheRoom() =
        runTest {
            val preview = applier(withCommons = false).preview(invite(TOKENED, secret, "Home"))
            assertNull(preview.room)
            applier(withCommons = false).apply(preview)
            coVerify { settings.addSpoolUrl(TOKENED) }
            coVerify { mesh.refreshRelays() }
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
        }

    @Test
    fun theRoomSecretIsCarriedIntact() =
        runTest {
            val preview = applier().preview(invite(TOKENED, secret))
            assertArrayEquals(secret, preview.room!!.secret)
        }

    private companion object {
        const val NOW = 1_757_800_000_000L
        const val TOKENED = "wss://home.example.org/spool/v1?k=t0ken"
        const val UNTOKENED = "wss://home.example.org/spool/v1"
    }
}
