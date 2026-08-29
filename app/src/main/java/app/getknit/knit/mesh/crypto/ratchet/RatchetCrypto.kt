package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.crypto.AesGcm
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519

/**
 * The primitive layer of the DM epoch-rekey ratchet (crypto scheme v2 — see
 * docs/FORWARD_SECRECY_RATCHET.md): X3DH-style session bootstrap, per-epoch key derivation, and the
 * forward-only message-key chain. Pure byte-array functions over Tink's subtle X25519/HKDF (present
 * since minSdk 29 can't use platform XDH), so everything here runs unchanged under JVM unit tests and
 * doubles as the normative reference for a non-Tink (iOS CryptoKit) implementation.
 *
 * Domain separation: every derivation is labeled under `knit/dm/v2/...` — plus the two `knit/dm/v3/...`
 * labels the v3 scheme adds on top of the unchanged v2 chain (ADR 059) — disjoint from RFC 9180's HPKE
 * labels, so reusing the X25519 identity key for both HPKE (v1) and X3DH (v2) cannot cross-derive.
 *
 * All functions are deterministic except the keypair helpers; none of them touch Android, IO, or state.
 */
@Suppress("TooManyFunctions") // the primitive layer: one function per derivation, and v3 added two
object RatchetCrypto {
    const val KEY_BYTES = 32

    /** HKDF output: first 32 bytes key the epoch's message chain, second 32 feed the export API. */
    const val EPOCH_SECRET_BYTES = 64

    private const val MAC = "HMACSHA256"
    private val ZERO_SALT = ByteArray(KEY_BYTES)

    private val LABEL_X3DH = "knit/dm/v2/x3dh".toByteArray()
    private val LABEL_EPOCH = "knit/dm/v2/epoch".toByteArray()
    private val LABEL_MSG_KEY = "knit/dm/v2/mk".toByteArray()
    private val LABEL_CHAIN_KEY = "knit/dm/v2/ck".toByteArray()
    private val LABEL_SPK = "knit/dm/v2/spk".toByteArray()
    private val LABEL_EXPORT_ROOT = "knit/dm/v2/export/root".toByteArray()
    private val LABEL_EXPORT_EPOCH = "knit/dm/v2/export/epoch".toByteArray()
    private val LABEL_NONCE_V3 = "knit/dm/v3/nonce".toByteArray()
    private val LABEL_HEADER_V3 = "knit/dm/v3/hdr".toByteArray()

    /** An X25519 keypair as raw RFC 7748 bytes (the only key shape the v2 wire ever carries). */
    class KeyPair(
        val priv: ByteArray,
        val pub: ByteArray,
    )

    fun generateKeyPair(): KeyPair {
        val priv = X25519.generatePrivateKey()
        return KeyPair(priv, X25519.publicFromPrivate(priv))
    }

    /** Recomputes the public half of a stored raw X25519 private key (prekeys persist priv-only). */
    fun publicFromPrivate(priv: ByteArray): ByteArray = X25519.publicFromPrivate(priv)

    /** Raw X25519 shared secret; throws on an invalid public key (callers treat that as decrypt failure). */
    fun dh(
        priv: ByteArray,
        pub: ByteArray,
    ): ByteArray = X25519.computeSharedSecret(priv, pub)

    /**
     * Initiator side of the session bootstrap: three DHs against the responder's identity and signed
     * prekey. DH1 binds our identity, DH2 binds theirs, DH3 (ephemeral-prekey) is the forward-secrecy
     * core — no long-term-only pair can reconstruct the root once [ekPriv] and the prekey retire.
     */
    fun x3dhInitiate(
        ikPriv: ByteArray,
        ekPriv: ByteArray,
        peerIkPub: ByteArray,
        peerSpkPub: ByteArray,
    ): ByteArray =
        x3dhRoot(
            dh1 = dh(ikPriv, peerSpkPub),
            dh2 = dh(ekPriv, peerIkPub),
            dh3 = dh(ekPriv, peerSpkPub),
        )

    /** Responder mirror of [x3dhInitiate]; both sides derive the identical session root. */
    fun x3dhRespond(
        ikPriv: ByteArray,
        spkPriv: ByteArray,
        peerIkPub: ByteArray,
        peerEkPub: ByteArray,
    ): ByteArray =
        x3dhRoot(
            dh1 = dh(spkPriv, peerIkPub),
            dh2 = dh(ikPriv, peerEkPub),
            dh3 = dh(spkPriv, peerEkPub),
        )

    private fun x3dhRoot(
        dh1: ByteArray,
        dh2: ByteArray,
        dh3: ByteArray,
    ): ByteArray {
        // The 0xFF prefix pads the ikm the way X3DH does, keeping the first HKDF block distinct from
        // any raw-DH-only construction.
        val ikm = ByteArray(KEY_BYTES) { 0xFF.toByte() } + dh1 + dh2 + dh3
        return Hkdf.computeHkdf(MAC, ikm, ZERO_SALT, LABEL_X3DH, KEY_BYTES)
    }

    /** The two halves of a derived epoch secret. */
    class EpochKeys(
        val chainKey: ByteArray,
        val export: ByteArray,
    )

    /**
     * Derives one direction's epoch secret. [sessionRoot] salts the HKDF (static per session — a
     * deliberate non-chain: custody holes are permanent, so epochs must derive independently), and the
     * info binds direction + both epoch counters so no two epochs of a session can collide even if a
     * DH result repeated. [senderIsInitiator] is the epoch sender's role in the *session*, not who is
     * calling this function — both sides pass the same value for the same epoch.
     */
    fun deriveEpoch(
        sessionRoot: ByteArray,
        dhShared: ByteArray,
        senderIsInitiator: Boolean,
        senderEpoch: Int,
        baseEpoch: Int,
    ): EpochKeys {
        val info =
            LABEL_EPOCH +
                byteArrayOf(if (senderIsInitiator) 'i'.code.toByte() else 'r'.code.toByte()) +
                u32be(senderEpoch) + u32be(baseEpoch)
        val okm = Hkdf.computeHkdf(MAC, dhShared, sessionRoot, info, EPOCH_SECRET_BYTES)
        return EpochKeys(chainKey = okm.copyOfRange(0, KEY_BYTES), export = okm.copyOfRange(KEY_BYTES, EPOCH_SECRET_BYTES))
    }

    /** The AES-256-GCM key for chain index `n`, given `chainKey_n`. Single-use; deleted after decrypt. */
    fun messageKey(chainKey: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, chainKey, ZERO_SALT, LABEL_MSG_KEY, KEY_BYTES)

    /** Advances the forward-only chain: `chainKey_{n+1}` from `chainKey_n` (one-way; enables delete-as-you-go). */
    fun nextChainKey(chainKey: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, chainKey, ZERO_SALT, LABEL_CHAIN_KEY, KEY_BYTES)

    /**
     * The v3 AES-GCM nonce for one message (crypto scheme v3, ADR 059): derived, never carried. Derived from
     * the **message key** rather than the chain key so a stored skipped key — which is all
     * `ratchet_skipped_keys` keeps — can still open its frame, and so each root candidate / ladder rung
     * derives its own. [aad] is mixed in so that a send-chain rollback (a restored database re-sealing a
     * different plaintext under the same chain index) still gets a distinct nonce from the fresh frame id
     * the AAD carries — the same (key, nonce) posture v2's random IV had. Uniqueness under one key
     * otherwise follows from the key itself being single-use.
     */
    fun messageNonce(
        msgKey: ByteArray,
        aad: ByteArray,
    ): ByteArray = Hkdf.computeHkdf(MAC, msgKey, ZERO_SALT, LABEL_NONCE_V3 + aad, AesGcm.IV_BYTES)

    /**
     * The bytes a v3 frame binds its ratchet header with, appended to the caller's AAD. The v2 header needed
     * no integrity mechanism of its own because the frame's Ed25519 signature covered it; the v3 unsigned
     * point-to-point tick has no signature, and `flags` (bit 0 = a reset request) and `init.at` (the
     * session-establishment clock) are the two header fields the derived key does not already bind — so the
     * AEAD binds the whole header explicitly. An explicit layout, not the CBOR bytes, so it never depends on
     * an encoder reproducing them canonically.
     */
    fun headerBindingBytes(header: RatchetEngine.FrameHeader): ByteArray {
        val init = header.init
        val initBytes =
            if (init == null) byteArrayOf(0) else byteArrayOf(1) + init.eph + u32be(init.pkid) + u64be(init.at)
        return LABEL_HEADER_V3 + u32be(header.se) + header.ek + u32be(header.pe) + u32be(header.n) +
            byteArrayOf(header.flags.toByte()) + initBytes
    }

    /**
     * The bytes a signed prekey's detached Ed25519 signature covers. Detached (rather than leaning on
     * the profile frame signature alone) so a prekey stored apart from its frame stays re-verifiable.
     */
    fun spkSigningBytes(
        id: Int,
        pub: ByteArray,
    ): ByteArray = LABEL_SPK + u32be(id) + pub

    /**
     * Stable per-session export secret (the spool-plane `pairwiseRoot`); both sides derive the same
     * value. Consumed by `ScopeCrypto` for DM scope derivation (docs/SPOOL_PROTOCOL.md §3).
     */
    fun exportRoot(sessionRoot: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, sessionRoot, ZERO_SALT, LABEL_EXPORT_ROOT, KEY_BYTES)

    /**
     * Per-epoch export secret — reserved for an epoch-keyed outer seal (`sealv = 2`, extension
     * register of docs/SPOOL_PROTOCOL.md); the v1 seal is scope-static, so this stays API-only.
     */
    fun exportEpochSeal(epochExport: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, epochExport, ZERO_SALT, LABEL_EXPORT_EPOCH, KEY_BYTES)

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 24/16/8 would only obscure them
    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    @Suppress("MagicNumber") // as above, eight lanes
    private fun u64be(value: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { i -> (value ushr (56 - 8 * i)).toByte() }
}
