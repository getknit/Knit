package app.getknit.knit.mesh.crypto.ratchet

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519

/**
 * The primitive layer of the DM epoch-rekey ratchet (crypto scheme v2 — see
 * docs/FORWARD_SECRECY_RATCHET.md): X3DH-style session bootstrap, per-epoch key derivation, and the
 * forward-only message-key chain. Pure byte-array functions over Tink's subtle X25519/HKDF (present
 * since minSdk 29 can't use platform XDH), so everything here runs unchanged under JVM unit tests and
 * doubles as the normative reference for a non-Tink (iOS CryptoKit) implementation.
 *
 * Domain separation: every derivation is labeled under `knit/dm/v2/...`, disjoint from RFC 9180's HPKE
 * labels, so reusing the X25519 identity key for both HPKE (v1) and X3DH (v2) cannot cross-derive.
 *
 * All functions are deterministic except the keypair helpers; none of them touch Android, IO, or state.
 */
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
     * The bytes a signed prekey's detached Ed25519 signature covers. Detached (rather than leaning on
     * the profile frame signature alone) so a prekey stored apart from its frame stays re-verifiable.
     */
    fun spkSigningBytes(
        id: Int,
        pub: ByteArray,
    ): ByteArray = LABEL_SPK + u32be(id) + pub

    /** Stable per-session export secret (the spool-plane `pairwiseRoot`); both sides derive the same value. */
    fun exportRoot(sessionRoot: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, sessionRoot, ZERO_SALT, LABEL_EXPORT_ROOT, KEY_BYTES)

    /** Per-epoch export secret (future relay sealing keys rotate with this). */
    fun exportEpochSeal(epochExport: ByteArray): ByteArray = Hkdf.computeHkdf(MAC, epochExport, ZERO_SALT, LABEL_EXPORT_EPOCH, KEY_BYTES)

    @Suppress("MagicNumber") // big-endian byte-lane shifts; naming 24/16/8 would only obscure them
    private fun u32be(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
}
