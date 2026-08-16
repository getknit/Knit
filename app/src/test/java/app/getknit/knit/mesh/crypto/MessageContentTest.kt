package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupRootPayload
import app.getknit.knit.mesh.protocol.GroupSeed
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ReactionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The plaintext content schema: CBOR round-trip and the delivery-time version gate (unknown → dropped). */
class MessageContentTest {
    @Test
    fun encodeThenDecodeRoundTripsEveryField() {
        val original =
            MessageContent(
                body = "hi",
                mentions = listOf(Mention("bob00000", "Bob")),
                attachmentHash = "hash",
                attachmentMime = "image/jpeg",
                attachmentKey = "key",
            )
        val decoded = MessageContent.decode(original.encode())!!
        assertEquals("hi", decoded.body)
        assertEquals(listOf(Mention("bob00000", "Bob")), decoded.mentions)
        assertEquals("hash", decoded.attachmentHash)
        assertEquals("image/jpeg", decoded.attachmentMime)
        assertEquals("key", decoded.attachmentKey)
    }

    @Test
    fun theCurrentVersionIsSupported() {
        assertTrue(MessageContent(body = "hi").isSupported()) // v defaults to VERSION
    }

    @Test
    fun aFutureContentSchemaVersionIsNotSupported() {
        assertFalse(MessageContent(v = MessageContent.MAX_SUPPORTED + 1, body = "hi").isSupported())
    }

    @Test
    fun decodingGarbageReturnsNullRatherThanThrowing() {
        assertNull(MessageContent.decode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun theControlMarkerRoundTripsAndDefaultsToNull() {
        // Additive field under the same schema version: a v1-era decoder ignores it, an ordinary
        // message omits it (encodeDefaults = false), and a reset frame carries it inside the ciphertext.
        val reset = MessageContent.decode(MessageContent(body = "", ctl = MessageContent.CTL_SESSION_RESET).encode())!!
        assertEquals(MessageContent.CTL_SESSION_RESET, reset.ctl)
        assertTrue(reset.isSupported())
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.ctl)
    }

    @Test
    fun theGroupKeyPayloadRoundTripsThroughEveryCtlShape() {
        val seed = GroupSeed(epoch = 3, seed = ByteArray(32) { 5 }, mintedAt = 77L)
        val dist =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload("g-1", keys = listOf(seed))).encode(),
            )!!
        assertEquals(MessageContent.CTL_GROUP_KEY, dist.ctl)
        assertEquals("g-1", dist.gk?.groupId)
        assertEquals(
            3,
            dist.gk
                ?.keys
                ?.single()
                ?.epoch,
        )
        assertEquals(
            77L,
            dist.gk
                ?.keys
                ?.single()
                ?.mintedAt,
        )
        assertTrue(
            ByteArray(32) { 5 }.contentEquals(
                dist.gk!!
                    .keys
                    .single()
                    .seed,
            ),
        )

        val req =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_REQ, gk = GroupKeyPayload("g-1")).encode(),
            )!!
        assertEquals(MessageContent.CTL_GROUP_KEY_REQ, req.ctl)
        assertTrue(req.gk!!.keys.isEmpty())

        val ack =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY_ACK, gk = GroupKeyPayload("g-1", ackEpoch = 3)).encode(),
            )!!
        assertEquals(3, ack.gk?.ackEpoch)

        // An ordinary message carries no gk (encodeDefaults = false keeps it off the wire entirely).
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.gk)

        // A seed distribution carries no root unless one is gossiped alongside it.
        assertNull(dist.gk?.gr)
    }

    @Test
    fun theGroupRootRidesTheKeyCtlWithAndWithoutSeeds() {
        // The root-only distribution (docs/SPOOL_PROTOCOL.md §3.2): `keys` empty is the normal shape for a
        // member that holds a root but has never sealed a group frame, so `gr` must survive it — a receiver
        // that adopts roots only inside its seed branch would drop exactly these.
        val root = GroupRootPayload(root = ByteArray(32) { 9 }, version = 2, minter = "bbbbbbbbbbbbbbbbbbbbbbbbbb")
        val rootOnly =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload("g-1", gr = root)).encode(),
            )!!
        assertTrue(rootOnly.gk!!.keys.isEmpty())
        assertEquals(2, rootOnly.gk?.gr?.version)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbb", rootOnly.gk?.gr?.minter)
        assertTrue(ByteArray(32) { 9 }.contentEquals(rootOnly.gk!!.gr!!.root))

        // ...and the ordinary shape: seeds and root together on one ctl DM.
        val both =
            MessageContent.decode(
                MessageContent(
                    body = "",
                    ctl = MessageContent.CTL_GROUP_KEY,
                    gk =
                        GroupKeyPayload(
                            "g-1",
                            keys = listOf(GroupSeed(epoch = 4, seed = ByteArray(32) { 1 }, mintedAt = 88L)),
                            gr = root,
                        ),
                ).encode(),
            )!!
        assertEquals(
            4,
            both.gk
                ?.keys
                ?.single()
                ?.epoch,
        )
        assertEquals(2, both.gk?.gr?.version)
    }

    @Test
    fun theReceiptCtlRoundTripsWithItsAckId() {
        val receipt =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = "m-42").encode(),
            )!!
        assertEquals(MessageContent.CTL_RECEIPT, receipt.ctl)
        assertEquals("m-42", receipt.ack)
        assertTrue(receipt.isSupported()) // additive fields, same schema version
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.ack)
    }

    @Test
    fun theReactionCtlRoundTripsIncludingRetraction() {
        val reaction =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m-42", "👍")).encode(),
            )!!
        assertEquals(MessageContent.CTL_REACTION, reaction.ctl)
        assertEquals("m-42", reaction.rp?.messageId)
        assertEquals("👍", reaction.rp?.emoji)

        // A retraction is emoji = null INSIDE a present rp — distinguishable from "no reaction payload"
        // because rp itself is non-null (encodeDefaults = false omits the null emoji key, and a missing
        // key decodes back to null; no reaction legitimately has no emoji, so the shape is unambiguous).
        val retraction =
            MessageContent.decode(
                MessageContent(body = "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m-42")).encode(),
            )!!
        assertEquals("m-42", retraction.rp?.messageId)
        assertNull(retraction.rp?.emoji)
        assertNull(MessageContent.decode(MessageContent(body = "hi").encode())!!.rp)
    }

    @Test
    fun aPlainMessageEncodingIsByteIdenticalWithTheNewFieldsDefaulted() {
        // WIRE_COMPAT rule 1 proof for the ack/rp additions: an ordinary message's bytes are unchanged
        // by the new nullable fields (encodeDefaults = false), so pre-change golden vectors — and every
        // deployed build's expectations — still hold. Pinned against the hand-computed CBOR of
        // {"body": "hi"}: definite-length map, one text key, one text value.
        val plain = MessageContent(body = "hi").encode()
        assertTrue(
            byteArrayOf(
                0xA1.toByte(),
                0x64,
                'b'.code.toByte(),
                'o'.code.toByte(),
                'd'.code.toByte(),
                'y'.code.toByte(),
                0x62,
                'h'.code.toByte(),
                'i'.code.toByte(),
            ).contentEquals(plain),
        )
    }
}
