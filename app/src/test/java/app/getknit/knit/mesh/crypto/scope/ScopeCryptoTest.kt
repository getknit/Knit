package app.getknit.knit.mesh.crypto.scope

import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Behavior anchors for [ScopeCrypto]. Every derivation is cross-checked against [referenceHkdf] — a
 * from-scratch RFC 5869 HKDF on bare [javax.crypto.Mac], never Tink validating Tink — and the seal's
 * two load-bearing properties are proven directly: determinism (any member seals a frame to the same
 * blob) and scope binding (a blob replanted into another scope fails to open). Byte-exact spec
 * vectors are pinned separately in [ScopeVectorTest]; docs/SPOOL_PROTOCOL.md is the normative spec.
 */
class ScopeCryptoTest {
    @Test
    fun dmScopeIdMatchesTheReferenceAndIsOrderInsensitive() {
        val root = deterministicBytes(1)

        val id = ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)

        val info = "knit/scope/v1/dm/id".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        assertArrayEquals(referenceHkdf(root, ByteArray(32), info, 32), id)
        assertArrayEquals(id, ScopeCrypto.dmScopeId(root, NODE_B, NODE_A))
        assertEquals(ScopeCrypto.SCOPE_ID_BYTES, id.size)
    }

    @Test
    fun dmSealKeysMatchTheReferenceAndDifferFromTheScopeId() {
        val root = deterministicBytes(1)

        val keys = ScopeCrypto.dmSealKeys(root, NODE_B, NODE_A)

        val info = "knit/scope/v1/seal".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        val okm = referenceHkdf(root, ByteArray(32), info, 64)
        assertArrayEquals(okm.copyOfRange(0, 32), keys.sealKey)
        assertArrayEquals(okm.copyOfRange(32, 64), keys.nonceKey)
        assertFalse(keys.sealKey.contentEquals(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)))
        assertFalse(keys.sealKey.contentEquals(keys.nonceKey))
    }

    @Test
    fun groupDerivationsBindGroupIdAndRootVersion() {
        val root = deterministicBytes(2)

        val id = ScopeCrypto.groupScopeId(root, GROUP_ID, rootVersion = 1)

        val info = "knit/scope/v1/group/id".toByteArray() + "$GROUP_ID|".toByteArray() + byteArrayOf(0, 0, 0, 1)
        assertArrayEquals(referenceHkdf(root, ByteArray(32), info, 32), id)
        assertFalse(id.contentEquals(ScopeCrypto.groupScopeId(root, GROUP_ID, rootVersion = 2)))
        assertFalse(id.contentEquals(ScopeCrypto.groupScopeId(root, "g-ffeeddccbbaa99887766554433", rootVersion = 1)))
        assertFalse(
            ScopeCrypto.groupSealKeys(root, GROUP_ID, rootVersion = 1).sealKey.contentEquals(
                ScopeCrypto.groupSealKeys(root, GROUP_ID, rootVersion = 2).sealKey,
            ),
        )
    }

    @Test
    fun derivationsStayDomainSeparatedFromTheRatchetExports() {
        val root = deterministicBytes(3)

        assertFalse(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B).contentEquals(RatchetCrypto.exportRoot(root)))
        assertFalse(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B).contentEquals(RatchetCrypto.exportEpochSeal(root)))
        assertFalse(ScopeCrypto.dmSealKeys(root, NODE_A, NODE_B).sealKey.contentEquals(RatchetCrypto.exportRoot(root)))
        assertFalse(ScopeCrypto.groupScopeId(root, GROUP_ID, 1).contentEquals(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)))
    }

    @Test
    fun sealIsDeterministicAndRoundTrips() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val sig = deterministicBytes(4, 64)
        val signed = deterministicBytes(5, 40)

        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)

        assertArrayEquals(blob, ScopeCrypto.seal(keys, scopeId, sig, signed))
        assertArrayEquals(ScopeCrypto.blobId(blob), ScopeCrypto.blobId(ScopeCrypto.seal(keys, scopeId, sig, signed)))
        val opened = ScopeCrypto.open(keys, scopeId, blob)
        assertArrayEquals(sig, opened.sig)
        assertArrayEquals(signed, opened.signed)
    }

    @Test
    fun sealedNonceMatchesTheReferenceDerivation() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val sig = deterministicBytes(4, 64)
        val signed = deterministicBytes(5, 40)

        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)

        val ptHash = MessageDigest.getInstance("SHA-256").digest(sig + signed)
        val nonce = referenceHkdf(keys.nonceKey, ByteArray(32), "knit/scope/v1/nonce".toByteArray() + ptHash, 12)
        assertEquals(ScopeCrypto.SEAL_VERSION, blob[0])
        assertArrayEquals(nonce, blob.copyOfRange(1, 1 + ScopeCrypto.NONCE_BYTES))
    }

    @Test
    fun openRejectsTamperWrongScopeAndWrongVersion() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val blob = ScopeCrypto.seal(keys, scopeId, deterministicBytes(4, 64), deterministicBytes(5, 40))

        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.open(keys, scopeId, tampered) }

        val otherScope = ScopeCrypto.dmScopeId(deterministicBytes(2), NODE_A, NODE_B)
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.open(keys, otherScope, blob) }

        val wrongVersion = blob.copyOf().also { it[0] = 2 }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.open(keys, scopeId, wrongVersion) }
    }

    @Test
    fun scopeDigestFoldsOrderIndependentlyAndSelfInverse() {
        val a = deterministicBytes(11)
        val b = deterministicBytes(12)
        val c = deterministicBytes(13)

        assertEquals(0L, ScopeCrypto.scopeDigest(emptyList()))
        assertEquals(ScopeCrypto.scopeDigest(listOf(a, b, c)), ScopeCrypto.scopeDigest(listOf(c, a, b)))
        assertEquals(0L, ScopeCrypto.scopeDigest(listOf(a, a)))
        assertEquals(
            ScopeCrypto.fnv64(b),
            ScopeCrypto.scopeDigest(listOf(a, b)) xor ScopeCrypto.fnv64(a),
        )
        assertEquals(ScopeCrypto.fnv64(a) xor ScopeCrypto.fnv64(b) xor ScopeCrypto.fnv64(c), ScopeCrypto.scopeDigest(listOf(a, b, c)))
    }

    @Test
    fun digestBytesRoundTripsBigEndian() {
        assertArrayEquals(ByteArray(8), ScopeCrypto.digestBytes(0L))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 2),
            ScopeCrypto.digestBytes(0x0102L),
        )
        for (value in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL)) {
            assertEquals(value, ScopeCrypto.digestValue(ScopeCrypto.digestBytes(value)))
        }
    }

    private companion object {
        // Valid-shape fixtures: 26-char base32 node ids, `g-` + 24-hex group id.
        const val NODE_A = "aaaaabbbbbcccccdddddeeeeef"
        const val NODE_B = "zzzzzyyyyyxxxxxwwwwwvvvvvu"
        const val GROUP_ID = "g-00112233445566778899aabb"

        fun deterministicBytes(
            seed: Int,
            n: Int = 32,
        ): ByteArray = ByteArray(n) { ((it * 31 + seed * 131) and 0xFF).toByte() }

        /** Independent RFC 5869 HKDF-SHA256 (extract then expand) on bare javax.crypto — no Tink. */
        fun referenceHkdf(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val prk = hmac(salt, ikm)
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                val take = minOf(previous.size, length - filled)
                System.arraycopy(previous, 0, out, filled, take)
                filled += take
                counter++
            }
            return out
        }

        fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
