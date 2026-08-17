package app.getknit.knit.mesh.crypto.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors for the scope crypto — the byte-exact fixtures mirrored in docs/SPOOL_PROTOCOL.md
 * §13 (keep the two in lockstep; the spec quotes these values verbatim). Pins every derivation, the
 * deterministic seal, the set digest, and one mined PoW stamp against fixed inputs, so an accidental
 * label/layout/fold change fails loudly and a non-Kotlin implementation has known answers to validate
 * against. To regenerate after an *intended* scheme change, temporarily print [vectors] and paste the
 * new hex here — and update the spec appendix in the same change.
 */
class ScopeVectorTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private val pairwiseRoot = bytes(32, 1)
    private val groupRoot = bytes(32, 2)
    private val powScopeId = bytes(32, 9)

    private fun dmKeys() = ScopeCrypto.dmSealKeys(pairwiseRoot, NODE_A, NODE_B)

    private fun dmScopeId() = ScopeCrypto.dmScopeId(pairwiseRoot, NODE_A, NODE_B)

    private fun sealedBlob() = ScopeCrypto.seal(dmKeys(), dmScopeId(), sig = bytes(64, 4), signed = bytes(40, 5))

    // A one-chunk attachment: `total = 1` makes chunk 0 the final chunk, which is the one that may be
    // short, so the vector stays quotable instead of carrying 48 KiB of fixture.
    private val attachHash = bytes(32, 6)

    private fun sealedChunk() = ScopeCrypto.sealChunk(dmKeys(), dmScopeId(), attachHash, index = 0, total = 1, chunk = bytes(48, 7))

    /** Every pinned value as lowercase hex, in a stable order. */
    private fun vectors(): Map<String, String> {
        val dmKeys = dmKeys()
        val groupKeys = ScopeCrypto.groupSealKeys(groupRoot, GROUP_ID, rootVersion = 1)
        val blob = sealedBlob()
        return linkedMapOf(
            "dmScopeId" to dmScopeId().toHex(),
            "dmSealKey" to dmKeys.sealKey.toHex(),
            "dmNonceKey" to dmKeys.nonceKey.toHex(),
            "groupScopeIdV1" to ScopeCrypto.groupScopeId(groupRoot, GROUP_ID, rootVersion = 1).toHex(),
            "groupScopeIdV2" to ScopeCrypto.groupScopeId(groupRoot, GROUP_ID, rootVersion = 2).toHex(),
            "groupSealKeyV1" to groupKeys.sealKey.toHex(),
            "groupNonceKeyV1" to groupKeys.nonceKey.toHex(),
            "sealBlob" to blob.toHex(),
            "sealBlobId" to ScopeCrypto.blobId(blob).toHex(),
            "attachId" to ScopeCrypto.attachmentId(dmKeys, dmScopeId(), attachHash).toHex(),
            "attachChunk" to sealedChunk().toHex(),
            "attachChunkId" to ScopeCrypto.blobId(sealedChunk()).toHex(),
            "digestSet" to ScopeCrypto.digestBytes(ScopeCrypto.scopeDigest(listOf(bytes(32, 11), bytes(32, 12), bytes(32, 13)))).toHex(),
            "powDigest" to SpoolPow.digest(powScopeId, day = POW_DAY, n = POW_N).toHex(),
        )
    }

    @Test
    fun everyScopeVectorMatchesItsPinnedHex() {
        val actual = vectors()
        assertEquals(EXPECTED.keys.toList(), actual.keys.toList())
        for ((name, hex) in actual) {
            assertEquals("scope vector '$name' drifted — an unintended scheme change", EXPECTED.getValue(name), hex)
        }
    }

    @Test
    fun theEmptyDigestIsZero() {
        assertEquals(0L, ScopeCrypto.scopeDigest(emptyList()))
        assertEquals("0000000000000000", ScopeCrypto.digestBytes(0L).toHex())
    }

    @Test
    fun thePinnedPowStampIsTheSmallestAndVerifies() {
        assertEquals(POW_N, SpoolPow.stamp(powScopeId, day = POW_DAY, bits = POW_BITS, maxAttempts = 1_000_000))
        assertTrue(SpoolPow.verify(powScopeId, day = POW_DAY, n = POW_N, bits = POW_BITS))
        val exactBits = SpoolPow.leadingZeroBits(SpoolPow.digest(powScopeId, POW_DAY, POW_N))
        assertFalse(SpoolPow.verify(powScopeId, day = POW_DAY, n = POW_N, bits = exactBits + 1))
    }

    private companion object {
        // Valid-shape fixtures: 26-char base32 node ids, `g-` + 24-hex group id, fixed UTC day.
        const val NODE_A = "aaaaabbbbbcccccdddddeeeeef"
        const val NODE_B = "zzzzzyyyyyxxxxxwwwwwvvvvvu"
        const val GROUP_ID = "g-00112233445566778899aabb"
        const val POW_DAY = 20_680L
        const val POW_BITS = 8
        const val POW_N = 8L

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        val EXPECTED =
            mapOf(
                "dmScopeId" to "aeced0ad65f9e416c3a4d6015ff6bd61849df5bcaf89b5a6f19aa9d654e7a8b2",
                "dmSealKey" to "fe7c47b82425bb4dbbd224ca192bf81131bdae07299380728b6eb3721d82eac7",
                "dmNonceKey" to "1704dfd72d5f529e8491784d17ed69e10fa7cbc2236667c23c77638dfd645dbe",
                "groupScopeIdV1" to "c5c544c7c4cb09c72557075ea90adc26b9b8bfa2676d227ef41a581f8c30f53d",
                "groupScopeIdV2" to "8ea040bce4597fb6d08dabd50ddc2342fb79775134f7b81de97125847589fef1",
                "groupSealKeyV1" to "b7a89432dc831b4035b8bb4709932e696cfe635b26ace09b448b4c600748eb4d",
                "groupNonceKeyV1" to "a35ac015c70ba45bdbb88b23d48d7ea60933fd311605daf7f75f1540c15f28ce",
                "sealBlob" to
                    "01e6844e8145bbc9581e53f9b0c4019dba5968bb7216685432e7412e1e9a56c813" +
                    "6af0829fdea4c8613b18b7df038f08613fa34fd474f93da53da1a05c0350bf9b68" +
                    "1290b880083a5593839b08b2496f79d7ddcaefc40943d1c0757ce594a12326d551" +
                    "e07a62528d62744ef30b9b24bea8a58856a6545436d099519a1706e1308b3ffe432e",
                "sealBlobId" to "8e5c2b6d8be66bb1204b644ebcc62f923bb27b659ecffb9344d35f7eb930d9c2",
                "attachId" to "4bb7dde9341d80ff87ea9f6709699f68f859ff9268fac97aa809e0f8c8d48bb1",
                "attachChunk" to
                    "036f83df42c1392e66eb87ae260f86d05080e007e9d59c6502eca05fd814664927" +
                    "416d29899375df3405d1564e7122a16eb5a095169dfa56d078b24fcae72a6c2c10" +
                    "89f75ffdd9c427706abc7dba44a5cb2847c95128a2c1d5360cb13980a80f2800bd" +
                    "718b7686b39cce91674728902979241dd815",
                "attachChunkId" to "e21f04fd3f95cade1a9a7424f6ab9bb45a9e185c836524c9ae7920a8fdfe0c27",
                "digestSet" to "834b13d8dc060ce5",
                "powDigest" to "00b776b91276563998bb57f8f3f73a05e0d8afcd3dce8a2583d6d466aadb620e",
            )
    }
}
