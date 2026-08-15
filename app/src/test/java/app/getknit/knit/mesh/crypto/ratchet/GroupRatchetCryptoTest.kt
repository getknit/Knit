package app.getknit.knit.mesh.crypto.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the v3 group sender-key primitives (docs/GROUP_FORWARD_SECRECY.md) against an independent RFC
 * 5869 HKDF — the [RatchetCryptoTest] discipline. These vectors are the normative anchors for a
 * non-Tink (iOS CryptoKit) implementation.
 */
class GroupRatchetCryptoTest {
    @Test
    fun epochDerivationMatchesTheReference() {
        val seed = deterministicBytes(1)

        val keys = GroupRatchetCrypto.deriveEpoch(seed, groupId = "g-abc123", senderId = "alice000", epoch = 3)

        val info = "knit/group/v1/epoch".toByteArray() + "g-abc123|alice000|".toByteArray() + byteArrayOf(0, 0, 0, 3)
        val okm = referenceHkdf(seed, ByteArray(32), info, 64)
        assertArrayEquals(okm.copyOfRange(0, 32), keys.chainKey)
        assertArrayEquals(okm.copyOfRange(32, 64), keys.export)
    }

    @Test
    fun epochDerivationBindsGroupSenderAndEpoch() {
        val seed = deterministicBytes(2)
        val base = GroupRatchetCrypto.deriveEpoch(seed, "g-1", "alice000", epoch = 1)

        assertFalse(base.chainKey.contentEquals(GroupRatchetCrypto.deriveEpoch(seed, "g-2", "alice000", epoch = 1).chainKey))
        assertFalse(base.chainKey.contentEquals(GroupRatchetCrypto.deriveEpoch(seed, "g-1", "bob00000", epoch = 1).chainKey))
        assertFalse(base.chainKey.contentEquals(GroupRatchetCrypto.deriveEpoch(seed, "g-1", "alice000", epoch = 2).chainKey))
    }

    @Test
    fun chainStepsMatchTheReferenceAndStayDisjointFromTheDmLabels() {
        val chain0 = deterministicBytes(7)

        val msg0 = GroupRatchetCrypto.messageKey(chain0)
        val chain1 = GroupRatchetCrypto.nextChainKey(chain0)

        assertArrayEquals(referenceHkdf(chain0, ByteArray(32), "knit/group/v1/mk".toByteArray(), 32), msg0)
        assertArrayEquals(referenceHkdf(chain0, ByteArray(32), "knit/group/v1/ck".toByteArray(), 32), chain1)
        // Same input bytes under the DM labels must derive different keys — the domain separation that
        // makes accidental key reuse across the two schemes impossible.
        assertFalse(msg0.contentEquals(RatchetCrypto.messageKey(chain0)))
        assertFalse(chain1.contentEquals(RatchetCrypto.nextChainKey(chain0)))
        assertFalse(msg0.contentEquals(chain1))
    }

    @Test
    fun exportMatchesTheReferenceAndStaysDomainSeparated() {
        val export = deterministicBytes(3)

        assertArrayEquals(
            referenceHkdf(export, ByteArray(32), "knit/group/v1/export/epoch".toByteArray(), 32),
            GroupRatchetCrypto.exportEpochSeal(export),
        )
        assertFalse(GroupRatchetCrypto.exportEpochSeal(export).contentEquals(RatchetCrypto.exportEpochSeal(export)))
        assertFalse(GroupRatchetCrypto.exportEpochSeal(export).contentEquals(GroupRatchetCrypto.messageKey(export)))
    }

    @Test
    fun freshSeedsAreDistinctAndSized() {
        val a = GroupRatchetCrypto.newSeed()
        val b = GroupRatchetCrypto.newSeed()

        assertEquals(GroupRatchetCrypto.SEED_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }

    private companion object {
        fun deterministicBytes(seed: Int): ByteArray = ByteArray(32) { ((it * 31 + seed * 131) and 0xFF).toByte() }

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
