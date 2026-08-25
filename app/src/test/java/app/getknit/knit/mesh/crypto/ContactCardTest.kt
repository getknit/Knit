package app.getknit.knit.mesh.crypto

import app.getknit.knit.identity.NodeId
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * The contact card codec on plain JVM. Golden vectors (mirrored in docs/CONTACT_CARD.md — keep them in
 * lockstep; rows are add-never-move) pin the exact bytes a non-Kotlin port must reproduce; the rest pins
 * every accepted text form, every refusal, and that [ContactCard.parse] survives arbitrary input.
 */
class ContactCardTest {
    @Before
    fun tink() = TinkInit.ensure()

    /** Deterministic identity: Ed25519 from a fixture seed, X25519 from a fixture scalar. */
    private val signing = Ed25519Sign.KeyPair.newKeyPairFromSeed(bytes(32, 1))
    private val dhPriv = bytes(32, 2)
    private val bundle = PublicKeyBundle.fromRaw(signing.publicKey, X25519.publicFromPrivate(dhPriv))!!
    private val signer = Ed25519Sign(signing.privateKey)
    private val nodeId = NodeId.fromPublicKeyBundle(bundle.encoded)

    private fun sign(bytes: ByteArray): ByteArray = signer.sign(bytes)

    private fun fullCard() = ContactCard.encode(bundle, name = "Ann", spools = listOf(SPOOL), issuedAt = ISSUED_AT, sign = ::sign)

    private fun minimalCard() = ContactCard.encode(bundle, name = null, spools = emptyList(), issuedAt = 0L, sign = ::sign)

    private fun vectors(): Map<String, String> =
        linkedMapOf(
            "nodeId" to nodeId,
            "bundle" to bundle.encoded,
            "fullCard" to fullCard(),
            "minimalCard" to minimalCard(),
        )

    @Test
    fun everyCardVectorMatchesItsPinnedValue() {
        val actual = vectors()
        assertEquals(EXPECTED.keys.toList(), actual.keys.toList())
        for ((name, value) in actual) {
            assertEquals("card vector '$name' drifted — an unintended layout change", EXPECTED.getValue(name), value)
        }
    }

    @Test
    fun aCardRoundTripsThroughEveryAcceptedForm() {
        val compact = fullCard()
        val forms =
            listOf(
                compact,
                ContactCard.url(compact),
                ContactCard.schemeUrl(compact),
                "Add me on Knit: ${ContactCard.url(compact)} — thanks!",
                "  ${ContactCard.schemeUrl(compact)}\n",
            )
        for (form in forms) {
            val card = ContactCard.parse(form) as ContactCard.Parsed.Card
            assertEquals(form, nodeId, card.nodeId)
            assertEquals(bundle.encoded, card.bundle)
            assertEquals("Ann", card.name)
            assertEquals(listOf(SPOOL), card.spools)
            assertEquals(ISSUED_AT, card.issuedAt)
        }
        val minimal = ContactCard.parse(minimalCard()) as ContactCard.Parsed.Card
        assertEquals("", minimal.name)
        assertEquals(emptyList<String>(), minimal.spools)
        assertEquals(0L, minimal.issuedAt)
    }

    @Test
    fun theLegacyQrStringIsAcceptedAsANamelessCard() {
        val card = ContactCard.parse(VerifyPayload.encode(nodeId, bundle.encoded)) as ContactCard.Parsed.Card
        assertEquals(nodeId, card.nodeId)
        assertEquals(bundle.encoded, card.bundle)
        assertEquals("", card.name)
        assertEquals(emptyList<String>(), card.spools)
        assertEquals(ContactCard.Reason.ID_MISMATCH, invalid(VerifyPayload.encode("a".repeat(26), bundle.encoded)))
        assertEquals(ContactCard.Reason.BAD_ID, invalid(VerifyPayload.encode("not-an-id", bundle.encoded)))
    }

    @Test
    fun theNameIsClampedAndTheRelayListCapped() {
        val compact =
            ContactCard.encode(
                bundle,
                name = "  Ann   Example " + "x".repeat(60),
                spools = List(6) { "wss://s$it.example/spool/v1" },
                issuedAt = 1L,
                sign = ::sign,
            )
        val card = ContactCard.parse(compact) as ContactCard.Parsed.Card
        assertEquals(32, card.name.length)
        assertTrue(card.name.startsWith("Ann Example x"))
        assertEquals(ContactCard.MAX_SPOOLS, card.spools.size)
    }

    @Test
    fun aTamperedCardIsRefused() {
        val compact = fullCard()
        val raw = Base64.getUrlDecoder().decode(compact)
        // Flip one byte at a time across the whole card: every position is either a signature failure,
        // a structural failure, or — for the id/key bytes — a mismatch. Never a Card, never a throw.
        for (i in raw.indices) {
            val mutated = raw.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val result = ContactCard.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(mutated))
            assertTrue("byte $i yielded $result", result is ContactCard.Parsed.Invalid)
        }
    }

    @Test
    fun aCardSignedByAnotherKeyIsRefusedEvenWhenItsBundleIsGenuine() {
        val other = Ed25519Sign(Ed25519Sign.KeyPair.newKeyPairFromSeed(bytes(32, 9)).privateKey)
        val forged = ContactCard.encode(bundle, name = "Mallory", spools = emptyList(), issuedAt = 5L) { other.sign(it) }
        assertEquals(ContactCard.Reason.BAD_SIGNATURE, invalid(forged))
    }

    @Test
    fun theRefusalReasonsAreSpecific() {
        assertEquals(ContactCard.Reason.NOT_A_CARD, invalid(""))
        assertEquals(ContactCard.Reason.NOT_A_CARD, invalid("hello there"))
        assertEquals(ContactCard.Reason.NOT_A_CARD, invalid("https://getknit.app/"))
        // A short bare token is a word or an id, never a card someone mangled.
        assertEquals(ContactCard.Reason.NOT_A_CARD, invalid("AAAA"))
        assertEquals(ContactCard.Reason.NOT_A_CARD, invalid("aaaaabbbbbcccccdddddeeeeef"))
        assertEquals(ContactCard.Reason.MALFORMED, invalid("A".repeat(260)))
        assertEquals(ContactCard.Reason.MALFORMED, invalid(ContactCard.url("AAAA")))
        assertEquals(ContactCard.Reason.TOO_LARGE, invalid("A".repeat(4000)))
    }

    @Test
    fun parseNeverThrowsOnArbitraryInput() {
        val random = Random(42)
        val compact = fullCard()
        val raw = Base64.getUrlDecoder().decode(compact)
        repeat(500) {
            val junk = ByteArray(random.nextInt(600)).also(random::nextBytes)
            ContactCard.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(junk))
            ContactCard.parse(String(junk, Charsets.ISO_8859_1))
        }
        for (len in 0 until raw.size) {
            ContactCard.parse(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.copyOf(len)))
            ContactCard.parse(compact.take(len))
        }
        ContactCard.parse(ContactCard.URL_PREFIX)
        ContactCard.parse(ContactCard.SCHEME_PREFIX + "!!!")
        assertTrue(ContactCard.looksLikeCard(ContactCard.url(compact)))
        assertTrue(!ContactCard.looksLikeCard("just some text"))
    }

    private fun invalid(text: String): ContactCard.Reason = (ContactCard.parse(text) as ContactCard.Parsed.Invalid).reason

    private companion object {
        const val SPOOL = "wss://lax.spool.getknit.app/spool/v1"
        const val ISSUED_AT = 1_756_100_000_000L

        fun bytes(
            n: Int,
            seed: Int,
        ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

        val EXPECTED =
            mapOf(
                "nodeId" to "4cgq2pnhh6p3j3afwsue3chrpi",
                "bundle" to
                    "omZzaWdQdWJYIOQDCZjP1a0XI8Fp+VaqC564YZtZkr1hLCr0KOvHn43wZ2hwa2VQdWJYIHPnmXHJ" +
                    "EQApcjYyqAtwe/T2LBJXYzRuHocY1sDcw6o6",
                "fullCard" to
                    "omRib2R5WKimYXYBYmlkeBo0Y2dxMnBuaGg2cDNqM2Fmd3N1ZTNjaHJwaWJwa1hA5AMJmM_VrRcj" +
                    "wWn5VqoLnrhhm1mSvWEsKvQo68efjfBz55lxyREAKXI2MqgLcHv09iwSV2M0bh6HGNbA3MOqOmRu" +
                    "YW1lY0FubmJzcIF4JHdzczovL2xheC5zcG9vbC5nZXRrbml0LmFwcC9zcG9vbC92MWNpYXQbAAAB" +
                    "mN-3eQBjc2lnWECn4VUyYreXsIMy1qJZ0Lan7cjpXGgbCijTCfSD68vSkGUrLis_L5ybjYzE86B8" +
                    "AR6MbQdZ3f6f62UsQPQyRPoH",
                "minimalCard" to
                    "omRib2R5WGijYXYBYmlkeBo0Y2dxMnBuaGg2cDNqM2Fmd3N1ZTNjaHJwaWJwa1hA5AMJmM_VrRcj" +
                    "wWn5VqoLnrhhm1mSvWEsKvQo68efjfBz55lxyREAKXI2MqgLcHv09iwSV2M0bh6HGNbA3MOqOmNz" +
                    "aWdYQLL2FdNZ6dxdvl8HfEBnbj8yNsSoGoCPELMEIXuO6kA04Ra6SeNRPkpg1UGmp1ZQgU3qjVOp" +
                    "M6JdH3puOvfihQA",
            )
    }
}
