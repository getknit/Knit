package app.getknit.knit.mesh.crypto.ratchet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The v2 ratchet primitive layer. Two independent anchors keep [RatchetCrypto] honest:
 * RFC 7748's X25519 test vectors pin the curve wiring, and [referenceHkdf] — a from-scratch RFC 5869
 * HKDF on top of plain [javax.crypto.Mac] — cross-checks every Tink `Hkdf.computeHkdf` call site
 * (extract/expand order, salt handling, output split). Deriving the same bytes through two unrelated
 * implementations is the known-answer test; these derivations are also the normative reference for a
 * future non-Tink (iOS) implementation, so label/layout drift fails here first.
 */
class RatchetCryptoTest {
    // --- RFC 7748 anchors ---

    @Test
    fun x25519MatchesRfc7748Vector1() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoord = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertArrayEquals(
            hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"),
            RatchetCrypto.dh(scalar, uCoord),
        )
    }

    @Test
    fun x25519MatchesRfc7748DiffieHellman() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertArrayEquals(alicePub, RatchetCrypto.publicFromPrivate(alicePriv))
        assertArrayEquals(shared, RatchetCrypto.dh(alicePriv, bobPub))
        assertArrayEquals(shared, RatchetCrypto.dh(bobPriv, alicePub))
    }

    // --- X3DH ---

    @Test
    fun x3dhBothSidesDeriveTheSameRoot() {
        val ikA = RatchetCrypto.generateKeyPair()
        val ikB = RatchetCrypto.generateKeyPair()
        val spkB = RatchetCrypto.generateKeyPair()
        val ekA = RatchetCrypto.generateKeyPair()

        val initiator = RatchetCrypto.x3dhInitiate(ikA.priv, ekA.priv, ikB.pub, spkB.pub)
        val responder = RatchetCrypto.x3dhRespond(ikB.priv, spkB.priv, ikA.pub, ekA.pub)

        assertArrayEquals(initiator, responder)
        assertEquals(RatchetCrypto.KEY_BYTES, initiator.size)
    }

    @Test
    fun x3dhMatchesTheReferenceDerivation() {
        val ikA = RatchetCrypto.generateKeyPair()
        val ikB = RatchetCrypto.generateKeyPair()
        val spkB = RatchetCrypto.generateKeyPair()
        val ekA = RatchetCrypto.generateKeyPair()

        // Recompute the root with the independent HKDF: 0xFF*32 ‖ DH1 ‖ DH2 ‖ DH3 under the x3dh label.
        val ikm =
            ByteArray(32) { 0xFF.toByte() } +
                RatchetCrypto.dh(ikA.priv, spkB.pub) +
                RatchetCrypto.dh(ekA.priv, ikB.pub) +
                RatchetCrypto.dh(ekA.priv, spkB.pub)
        val expected = referenceHkdf(ikm, ByteArray(32), "knit/dm/v2/x3dh".toByteArray(), 32)

        assertArrayEquals(expected, RatchetCrypto.x3dhInitiate(ikA.priv, ekA.priv, ikB.pub, spkB.pub))
    }

    @Test
    fun x3dhBindsEveryKey() {
        val ikA = RatchetCrypto.generateKeyPair()
        val ikB = RatchetCrypto.generateKeyPair()
        val spkB = RatchetCrypto.generateKeyPair()
        val ekA = RatchetCrypto.generateKeyPair()
        val root = RatchetCrypto.x3dhInitiate(ikA.priv, ekA.priv, ikB.pub, spkB.pub)

        val otherIk = RatchetCrypto.generateKeyPair()
        val otherEk = RatchetCrypto.generateKeyPair()
        val otherSpk = RatchetCrypto.generateKeyPair()
        assertFalse(root.contentEquals(RatchetCrypto.x3dhInitiate(otherIk.priv, ekA.priv, ikB.pub, spkB.pub)))
        assertFalse(root.contentEquals(RatchetCrypto.x3dhInitiate(ikA.priv, otherEk.priv, ikB.pub, spkB.pub)))
        assertFalse(root.contentEquals(RatchetCrypto.x3dhInitiate(ikA.priv, ekA.priv, ikB.pub, otherSpk.pub)))
    }

    // --- epoch + chain derivations ---

    @Test
    fun epochDerivationMatchesTheReference() {
        val root = deterministicBytes(1)
        val dh = deterministicBytes(2)

        val keys = RatchetCrypto.deriveEpoch(root, dh, senderIsInitiator = true, senderEpoch = 3, baseEpoch = 2)

        val info =
            "knit/dm/v2/epoch".toByteArray() + byteArrayOf('i'.code.toByte()) +
                byteArrayOf(0, 0, 0, 3) + byteArrayOf(0, 0, 0, 2)
        val okm = referenceHkdf(dh, root, info, 64)
        assertArrayEquals(okm.copyOfRange(0, 32), keys.chainKey)
        assertArrayEquals(okm.copyOfRange(32, 64), keys.export)
    }

    @Test
    fun epochDerivationBindsDirectionAndCounters() {
        val root = deterministicBytes(1)
        val dh = deterministicBytes(2)
        val base = RatchetCrypto.deriveEpoch(root, dh, senderIsInitiator = true, senderEpoch = 1, baseEpoch = 0)

        assertFalse(
            base.chainKey.contentEquals(
                RatchetCrypto.deriveEpoch(root, dh, senderIsInitiator = false, senderEpoch = 1, baseEpoch = 0).chainKey,
            ),
        )
        assertFalse(
            base.chainKey.contentEquals(
                RatchetCrypto.deriveEpoch(root, dh, senderIsInitiator = true, senderEpoch = 2, baseEpoch = 0).chainKey,
            ),
        )
        assertFalse(
            base.chainKey.contentEquals(
                RatchetCrypto.deriveEpoch(root, dh, senderIsInitiator = true, senderEpoch = 1, baseEpoch = 1).chainKey,
            ),
        )
    }

    @Test
    fun chainStepsMatchTheReferenceAndNeverCollide() {
        val chain0 = deterministicBytes(7)

        val msg0 = RatchetCrypto.messageKey(chain0)
        val chain1 = RatchetCrypto.nextChainKey(chain0)
        val msg1 = RatchetCrypto.messageKey(chain1)

        assertArrayEquals(referenceHkdf(chain0, ByteArray(32), "knit/dm/v2/mk".toByteArray(), 32), msg0)
        assertArrayEquals(referenceHkdf(chain0, ByteArray(32), "knit/dm/v2/ck".toByteArray(), 32), chain1)
        assertFalse(msg0.contentEquals(chain1))
        assertFalse(msg0.contentEquals(msg1))
    }

    // --- crypto scheme v3: the derived nonce and the header binding (ADR 059) ---

    @Test
    fun theV3NonceMatchesTheReferenceAndIsBoundToTheAad() {
        val msgKey = RatchetCrypto.messageKey(deterministicBytes(7))
        val aad = "m1|alice|100|bob".toByteArray()

        val nonce = RatchetCrypto.messageNonce(msgKey, aad)
        assertEquals(12, nonce.size)
        assertArrayEquals(referenceHkdf(msgKey, ByteArray(32), "knit/dm/v3/nonce".toByteArray() + aad, 12), nonce)
        // A different frame (a different AAD) under the same key derives a different nonce — the rollback posture.
        assertFalse(nonce.contentEquals(RatchetCrypto.messageNonce(msgKey, "m2|alice|100|bob".toByteArray())))
        // And the label keeps it apart from the chain derivations of the same key.
        assertFalse(nonce.contentEquals(RatchetCrypto.messageKey(msgKey).copyOf(12)))
        assertFalse(nonce.contentEquals(RatchetCrypto.nextChainKey(msgKey).copyOf(12)))
    }

    @Test
    fun theV3HeaderBindingLayoutIsFrozen() {
        val ek = deterministicBytes(5)
        val eph = deterministicBytes(6)
        val label = "knit/dm/v3/hdr".toByteArray()

        val bare =
            RatchetCrypto.headerBindingBytes(
                RatchetEngine.FrameHeader(se = 0x0102_0304, ek = ek, pe = 0x0000_0005, n = 0x0000_0600, flags = 1),
            )
        assertArrayEquals(label, bare.copyOfRange(0, label.size))
        var at = label.size
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bare.copyOfRange(at, at + 4))
        at += 4
        assertArrayEquals(ek, bare.copyOfRange(at, at + 32))
        at += 32
        assertArrayEquals(byteArrayOf(0, 0, 0, 5), bare.copyOfRange(at, at + 4))
        at += 4
        assertArrayEquals(byteArrayOf(0, 0, 6, 0), bare.copyOfRange(at, at + 4))
        at += 4
        assertArrayEquals(byteArrayOf(1, 0), bare.copyOfRange(at, at + 2)) // flags, then "no init"
        assertEquals(at + 2, bare.size)

        val withInit =
            RatchetCrypto.headerBindingBytes(
                RatchetEngine.FrameHeader(
                    se = 1,
                    ek = ek,
                    pe = 0,
                    n = 0,
                    init = RatchetEngine.InitPayload(eph, pkid = 0x0000_0007, at = 0x0102_0304_0506_0708L),
                ),
            )
        val tail = withInit.copyOfRange(withInit.size - (1 + 32 + 4 + 8), withInit.size)
        assertArrayEquals(byteArrayOf(1) + eph + byteArrayOf(0, 0, 0, 7) + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), tail)
        assertFalse(bare.contentEquals(withInit))
    }

    // --- signed-prekey bytes + exports ---

    @Test
    fun spkSigningBytesLayoutIsFrozen() {
        val pub = deterministicBytes(9)
        val bytes = RatchetCrypto.spkSigningBytes(id = 0x0102_0304, pub = pub)

        val label = "knit/dm/v2/spk".toByteArray()
        assertArrayEquals(label, bytes.copyOfRange(0, label.size))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(label.size, label.size + 4))
        assertArrayEquals(pub, bytes.copyOfRange(label.size + 4, bytes.size))
    }

    @Test
    fun exportsMatchTheReferenceAndStayDomainSeparated() {
        val root = deterministicBytes(3)

        assertArrayEquals(
            referenceHkdf(root, ByteArray(32), "knit/dm/v2/export/root".toByteArray(), 32),
            RatchetCrypto.exportRoot(root),
        )
        assertArrayEquals(
            referenceHkdf(root, ByteArray(32), "knit/dm/v2/export/epoch".toByteArray(), 32),
            RatchetCrypto.exportEpochSeal(root),
        )
        assertFalse(RatchetCrypto.exportRoot(root).contentEquals(RatchetCrypto.exportEpochSeal(root)))
        assertFalse(RatchetCrypto.exportRoot(root).contentEquals(RatchetCrypto.messageKey(root)))
    }

    private companion object {
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
