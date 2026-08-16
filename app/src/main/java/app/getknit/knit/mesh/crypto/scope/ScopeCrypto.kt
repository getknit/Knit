package app.getknit.knit.mesh.crypto.scope

import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitive layer of the spool plane's scope crypto (see docs/SPOOL_PROTOCOL.md, the normative
 * spec this object is the reference implementation of): scope-id and sealing-key derivation from the
 * ratchet export secrets, the deterministic outer seal over the frozen custody unit, content-addressed
 * blob ids, and the per-scope set digest. Pure byte-array functions over Tink's subtle HKDF and JDK
 * AES-GCM, so everything runs unchanged under JVM unit tests and doubles as the normative reference
 * for the spool daemon and any non-Kotlin implementation. API-only until `ScopeSync` lands (M3) — the
 * same no-consumer posture as the ratchet §8 exports this consumes.
 *
 * Domain separation: every derivation is labeled under `knit/scope/v1/...`, disjoint from
 * `knit/dm/v2/...`, `knit/group/v1/...`, and RFC 9180's HPKE labels. The DM input is
 * `RatchetCrypto.exportRoot`'s pairwiseRoot; the group input is the shared group root the spec's §3
 * mints — never a raw session or chain secret.
 *
 * The seal is deliberately deterministic (SIV-style synthetic nonce keyed by a scope secret): any
 * member sealing the same frame produces the identical blob, so spool-side dedup by [blobId] and
 * cross-uploader digest convergence hold by construction. See the spec's nonce-reuse analysis.
 *
 * All functions are deterministic; none of them touch Android, IO, or state.
 */
@Suppress("TooManyFunctions") // the spec's whole primitive surface — one object mirrors docs/SPOOL_PROTOCOL.md, the RatchetCrypto shape
object ScopeCrypto {
    const val KEY_BYTES = 32
    const val SCOPE_ID_BYTES = 32
    const val BLOB_ID_BYTES = 32
    const val NONCE_BYTES = 12
    const val DIGEST_BYTES = 8

    /** Raw Ed25519 signature length — the fixed prefix of every sealed plaintext. */
    const val SIG_BYTES = 64

    /** Outer-seal scheme version, the blob's first byte; `2` is reserved for an epoch-keyed seal. */
    const val SEAL_VERSION: Byte = 0x01

    /** HKDF output for [sealKeysInternal]: first 32 bytes seal, second 32 key the synthetic nonce. */
    const val SEAL_OKM_BYTES = 64

    private const val MAC = "HMACSHA256"
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    private val LABEL_DM_ID = "knit/scope/v1/dm/id".toByteArray()
    private val LABEL_GROUP_ID = "knit/scope/v1/group/id".toByteArray()
    private val LABEL_SEAL = "knit/scope/v1/seal".toByteArray()
    private val LABEL_NONCE = "knit/scope/v1/nonce".toByteArray()
    private val AAD_PREFIX = "knit/scope/v1".toByteArray()

    // FNV-1a 64-bit, mirrored from mesh/StoreDigest (same fold, raw-byte input instead of UTF-8).
    private const val FNV64_OFFSET = -0x340D631B7BDDDCDBL
    private const val FNV64_PRIME = 0x100000001B3L
    private const val BYTE_MASK = 0xFFL

    /** The two halves of a scope's sealing secret. */
    class SealKeys(
        val sealKey: ByteArray,
        val nonceKey: ByteArray,
    )

    /** The custody unit recovered from a sealed blob — `signed`/`sig` exactly as the mesh floods them. */
    class Unsealed(
        val sig: ByteArray,
        val signed: ByteArray,
    )

    /**
     * A DM pair's scope id: keyed by the pairwiseRoot (`RatchetCrypto.exportRoot`), with the sorted
     * node ids as defense-in-depth context. Symmetric — both members derive the same id; anyone
     * holding only the public ids cannot.
     */
    fun dmScopeId(
        pairwiseRoot: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): ByteArray = Hkdf.computeHkdf(MAC, pairwiseRoot, ZERO_SALT, LABEL_DM_ID + dmContext(nodeIdA, nodeIdB), SCOPE_ID_BYTES)

    /** A DM scope's sealing secret, derived beside [dmScopeId] from the same root and context. */
    fun dmSealKeys(
        pairwiseRoot: ByteArray,
        nodeIdA: String,
        nodeIdB: String,
    ): SealKeys = sealKeysInternal(pairwiseRoot, dmContext(nodeIdA, nodeIdB))

    /**
     * A group's scope id: keyed by the shared group root, with the group id and root version as
     * context. The version doubles as the scope epoch — a re-mint bumps it, so scope id and sealing
     * keys rotate together and the old scope is unlinkable to the new one.
     */
    fun groupScopeId(
        groupRoot: ByteArray,
        groupId: String,
        rootVersion: Int,
    ): ByteArray = Hkdf.computeHkdf(MAC, groupRoot, ZERO_SALT, LABEL_GROUP_ID + groupContext(groupId, rootVersion), SCOPE_ID_BYTES)

    /** A group scope's sealing secret, derived beside [groupScopeId] from the same root and context. */
    fun groupSealKeys(
        groupRoot: ByteArray,
        groupId: String,
        rootVersion: Int,
    ): SealKeys = sealKeysInternal(groupRoot, groupContext(groupId, rootVersion))

    /**
     * Seals a custody unit for a scope. Deterministic: the nonce is HKDF-derived from the plaintext
     * hash under the scope's [SealKeys.nonceKey] (keyed, so a spool holding candidate frame bytes
     * cannot confirm membership by recomputing it), and the aad binds [scopeId], so a blob replanted
     * into another scope fails authentication. [sig] must be the raw 64-byte frame signature.
     */
    fun seal(
        keys: SealKeys,
        scopeId: ByteArray,
        sig: ByteArray,
        signed: ByteArray,
    ): ByteArray {
        require(sig.size == SIG_BYTES) { "sig must be $SIG_BYTES raw Ed25519 bytes" }
        require(signed.isNotEmpty()) { "signed must be non-empty" }
        val pt = sig + signed
        val nonce = Hkdf.computeHkdf(MAC, keys.nonceKey, ZERO_SALT, LABEL_NONCE + sha256(pt), NONCE_BYTES)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_PREFIX + scopeId)
            }
        return byteArrayOf(SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    /**
     * Opens a sealed blob back into its custody unit. Throws [IllegalArgumentException] on a
     * structurally invalid blob (unknown seal version, truncated) and the JDK AEAD exception on a
     * wrong key, wrong scope, or tampered ciphertext. The caller still verifies the inner Ed25519
     * frame signature and the scope's frame-set rules — this only undoes the outer seal.
     */
    fun open(
        keys: SealKeys,
        scopeId: ByteArray,
        blob: ByteArray,
    ): Unsealed {
        require(blob.isNotEmpty() && blob[0] == SEAL_VERSION) { "unknown seal version" }
        require(blob.size > 1 + NONCE_BYTES + SIG_BYTES) { "blob too short" }
        val nonce = blob.copyOfRange(1, 1 + NONCE_BYTES)
        val ct = blob.copyOfRange(1 + NONCE_BYTES, blob.size)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD_PREFIX + scopeId)
            }
        val pt = cipher.doFinal(ct)
        require(pt.size > SIG_BYTES) { "sealed unit too short" }
        return Unsealed(sig = pt.copyOfRange(0, SIG_BYTES), signed = pt.copyOfRange(SIG_BYTES, pt.size))
    }

    /** A blob's content address: SHA-256 over the entire stored blob (version byte, nonce, ct). */
    fun blobId(blob: ByteArray): ByteArray = sha256(blob)

    /**
     * The per-scope set digest: XOR fold of [fnv64] over the raw blob ids — order-independent and
     * self-inverse, the `StoreDigest.fingerprint` shape over bytes instead of UTF-8 strings. Empty
     * set digests to 0.
     */
    fun scopeDigest(blobIds: Iterable<ByteArray>): Long = blobIds.fold(0L) { acc, id -> acc xor fnv64(id) }

    /** FNV-1a 64-bit over raw bytes (StoreDigest.hash64's input domain is UTF-8 strings; this one is bytes). */
    fun fnv64(bytes: ByteArray): Long {
        var h = FNV64_OFFSET
        for (b in bytes) {
            h = h xor (b.toLong() and BYTE_MASK)
            h *= FNV64_PRIME
        }
        return h
    }

    /** A digest's wire form: 8 bytes big-endian (a byte string on the wire, never a CBOR integer). */
    fun digestBytes(digest: Long): ByteArray = ByteArray(DIGEST_BYTES) { (digest ushr ((DIGEST_BYTES - 1 - it) * Byte.SIZE_BITS)).toByte() }

    /** Parses the 8-byte big-endian wire form back into the fold value. */
    fun digestValue(bytes: ByteArray): Long {
        require(bytes.size == DIGEST_BYTES) { "digest must be $DIGEST_BYTES bytes" }
        return bytes.fold(0L) { acc, b -> (acc shl Byte.SIZE_BITS) or (b.toLong() and BYTE_MASK) }
    }

    private fun sealKeysInternal(
        root: ByteArray,
        context: ByteArray,
    ): SealKeys {
        val okm = Hkdf.computeHkdf(MAC, root, ZERO_SALT, LABEL_SEAL + context, SEAL_OKM_BYTES)
        return SealKeys(sealKey = okm.copyOfRange(0, KEY_BYTES), nonceKey = okm.copyOfRange(KEY_BYTES, SEAL_OKM_BYTES))
    }

    // The `|` separator convention: it cannot occur in a base32 node id or a `g-`-hex group id, so
    // contexts cannot be made to alias each other (and the `g-` prefix keeps the two apart anyway).
    private fun dmContext(
        nodeIdA: String,
        nodeIdB: String,
    ): ByteArray {
        val low = minOf(nodeIdA, nodeIdB)
        val high = maxOf(nodeIdA, nodeIdB)
        return "$low|$high|".toByteArray()
    }

    private fun groupContext(
        groupId: String,
        rootVersion: Int,
    ): ByteArray = "$groupId|".toByteArray() + u32be(rootVersion)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 24/16/8 would only obscure them
    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
}
