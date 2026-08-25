package app.getknit.knit.contacts

import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The minter: our identity, our name, and our relays — minus any bearer token, and none at all with the plane off. */
class ContactCardsTest {
    private val identity = mockk<Identity>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(ByteArray(32) { it.toByte() })
    private val bundle = PublicKeyBundle.fromRaw(signing.publicKey, X25519.publicFromPrivate(ByteArray(32) { (it + 1).toByte() }))!!
    private val signer = Ed25519Sign(signing.privateKey)
    private val spoolEnabled = MutableStateFlow(true)
    private val spoolUrls =
        MutableStateFlow(setOf("wss://b.example/spool/v1", "wss://a.example/spool/v1?k=secret", "wss://c.example/spool/v1"))

    @Before
    fun setUp() {
        TinkInit.ensure()
        every { identity.publicKeyBundle() } returns bundle.encoded
        every { settings.displayName } returns MutableStateFlow("Ann")
        every { settings.spoolEnabled } returns spoolEnabled
        every { settings.spoolUrls } returns spoolUrls
    }

    private fun cards() = ContactCards(identity, settings, { signer.sign(it) }, clock = { 42L })

    @Test
    fun aMintedCardCarriesTheIdentityNameAndUntokenedRelays() =
        runTest {
            val minted = cards().mint()
            assertTrue(minted.url.startsWith(ContactCard.URL_PREFIX))
            assertTrue(minted.schemeUrl.startsWith(ContactCard.SCHEME_PREFIX))
            val card = ContactCard.parse(minted.url) as ContactCard.Parsed.Card
            assertEquals(bundle.encoded, card.bundle)
            assertEquals("Ann", card.name)
            assertEquals(listOf("wss://b.example/spool/v1", "wss://c.example/spool/v1"), card.spools)
            assertEquals(42L, card.issuedAt)
        }

    @Test
    fun noRelaysRideWhileThePlaneIsOff() =
        runTest {
            spoolEnabled.value = false
            val card = ContactCard.parse(cards().mint().compact) as ContactCard.Parsed.Card
            assertEquals(emptyList<String>(), card.spools)
        }
}
