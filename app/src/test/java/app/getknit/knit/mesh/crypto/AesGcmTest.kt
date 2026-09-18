package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * Directly exercises the AEAD primitive's authentication: the AAD, key, and ciphertext are all bound.
 * Each refusal is pinned to [AEADBadTagException] on the *decrypt* call alone — a broad "any exception,
 * anywhere in the test" would also pass on a broken `encrypt`, and would pass a `decrypt` that threw for
 * some reason other than the tag.
 */
class AesGcmTest {
    private val aad = "message-header".encodeToByteArray()

    @Test
    fun roundTripsWithTheMatchingKeyAndAad() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        assertEquals("secret", AesGcm.decrypt(key, iv, ct, aad).decodeToString())
    }

    @Test
    fun decryptingWithADifferentAadFails() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        // The AAD is authenticated (not encrypted): a mismatch must fail the tag, so the E2E header binding holds.
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(key, iv, ct, "tampered-header".encodeToByteArray()) }
    }

    @Test
    fun decryptingWithTheWrongKeyFails() {
        val (iv, ct) = AesGcm.encrypt(AesGcm.randomKey(), "secret".encodeToByteArray(), aad)
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(AesGcm.randomKey(), iv, ct, aad) }
    }

    @Test
    fun decryptingTamperedCiphertextFails() {
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "secret".encodeToByteArray(), aad)
        ct[ct.lastIndex] = (ct[ct.lastIndex] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(key, iv, ct, aad) }
    }

    @Test
    fun aFlippedBodyByteFailsTheTagLikeAFlippedTagByte() {
        // The tag covers the body, not only itself: a bit flipped in the ciphertext proper — well inside the
        // sixteen trailing tag bytes' reach — is refused, never decrypted to a corrupt plaintext.
        val key = AesGcm.randomKey()
        val (iv, ct) = AesGcm.encrypt(key, "a longer secret than one block".encodeToByteArray(), aad)
        ct[0] = (ct[0].toInt() xor 0x01).toByte()
        assertThrows(AEADBadTagException::class.java) { AesGcm.decrypt(key, iv, ct, aad) }
    }

    @Test
    fun aFreshIvPerEncryptionYieldsDifferentCiphertextForTheSameInput() {
        val key = AesGcm.randomKey()
        val a = AesGcm.encrypt(key, "same".encodeToByteArray(), aad)
        val b = AesGcm.encrypt(key, "same".encodeToByteArray(), aad)
        assertFalse("random IV per call", a.first.contentEquals(b.first))
        assertFalse("→ different ciphertext", a.second.contentEquals(b.second))
    }

    @Test
    fun aCallerChosenIvIsUsedAsGiven() {
        // The v3 ratchet derives its nonce from the message key and never sends it: the IV the caller passes
        // must be the one the ciphertext was made under, or the far end's derived nonce opens nothing.
        val key = AesGcm.randomKey()
        val iv = ByteArray(AesGcm.IV_BYTES) { it.toByte() }
        val (ivOut, ct) = AesGcm.encrypt(key, "derived".encodeToByteArray(), aad, iv)
        assertEquals(iv.toList(), ivOut.toList())
        assertEquals("derived", AesGcm.decrypt(key, iv, ct, aad).decodeToString())
    }
}
