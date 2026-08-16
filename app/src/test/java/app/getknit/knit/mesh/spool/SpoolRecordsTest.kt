package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anchors for the client↔spool record layer: byte-exact golden vectors for every record type (the
 * fixtures mirrored in docs/SPOOL_PROTOCOL.md §13 — keep them in lockstep), round-trips, and the two
 * tolerance properties additive evolution relies on (unknown discriminators are identifiable and
 * skippable, unknown fields are ignored). To regenerate after an *intended* record change,
 * temporarily print [vectors] and paste the new hex here.
 */
class SpoolRecordsTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    /** Every record type as a fixed instance → its encoded bytes, in a stable order. */
    @Suppress("LongMethod") // a flat list of one fixture per record type — clearer as one block than split
    private fun vectors(): Map<String, ByteArray> =
        linkedMapOf(
            "helloSpool" to
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        min = 1,
                        limits =
                            SpoolLimits(
                                maxBlob = 65_536,
                                maxRecord = 131_072,
                                maxScopes = 64,
                                maxPull = 64,
                                maxFramesCap = 1_000,
                                maxTtlMs = 604_800_000L,
                            ),
                        powBits = 20,
                    ),
                ),
            "helloClient" to SpoolCodec.encode(SpoolHello(t = SpoolRecordType.HELLO, v = SPOOL_RECORD_VERSION)),
            "sub" to
                SpoolCodec.encode(
                    SpoolSub(
                        t = SpoolRecordType.SUB,
                        q = 1L,
                        subs =
                            listOf(
                                ScopeSub(
                                    scope = bytes(32, 1),
                                    bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                                    pow = PowStamp(n = 42L, d = 20_680L),
                                ),
                            ),
                    ),
                ),
            "digest" to
                SpoolCodec.encode(
                    SpoolDigest(
                        t = SpoolRecordType.DIGEST,
                        scope = bytes(32, 1),
                        digest = bytes(8, 2),
                        count = 3,
                        full = false,
                        bounds = ScopeBounds(maxFrames = 400, ttlMs = 172_800_000L, maxBlob = 65_536),
                    ),
                ),
            "listRequest" to SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = 2L, scope = bytes(32, 1))),
            "listResponse" to
                SpoolCodec.encode(
                    SpoolList(
                        t = SpoolRecordType.LIST,
                        q = 2L,
                        scope = bytes(32, 1),
                        blobIds = listOf(bytes(32, 3), bytes(32, 4)),
                        tombstones = listOf(bytes(32, 5)),
                    ),
                ),
            "pull" to
                SpoolCodec.encode(
                    SpoolPull(t = SpoolRecordType.PULL, q = 3L, scope = bytes(32, 1), blobIds = listOf(bytes(32, 3))),
                ),
            "blob" to
                SpoolCodec.encode(
                    SpoolBlob(t = SpoolRecordType.BLOB, scope = bytes(32, 1), blobId = bytes(32, 3), data = bytes(48, 6)),
                ),
            "push" to
                SpoolCodec.encode(
                    SpoolPush(
                        t = SpoolRecordType.PUSH,
                        q = 4L,
                        scope = bytes(32, 1),
                        blobId = bytes(32, 3),
                        data = bytes(48, 6),
                        pow = PowStamp(n = 42L, d = 20_680L),
                    ),
                ),
            "event" to
                SpoolCodec.encode(
                    SpoolEvent(t = SpoolRecordType.EVENT, scope = bytes(32, 1), blobId = bytes(32, 3), data = bytes(48, 6)),
                ),
            "okBare" to SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = 3L)),
            "okMissing" to SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = 3L, missing = listOf(bytes(32, 4)))),
            "errScoped" to
                SpoolCodec.encode(
                    SpoolErr(
                        t = SpoolRecordType.ERR,
                        code = SpoolErrCode.TOMBSTONED,
                        q = 4L,
                        scope = bytes(32, 1),
                    ),
                ),
            "errRate" to
                SpoolCodec.encode(
                    SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.RATE, msg = "slow down", retryMs = 30_000L),
                ),
        )

    @Test
    fun everyRecordMatchesItsPinnedDefiniteLengthCbor() {
        val actual = vectors()
        assertEquals(EXPECTED.keys.toList(), actual.keys.toList())
        for ((name, encoded) in actual) {
            assertEquals("record vector '$name' drifted — an unintended record-layer change", EXPECTED.getValue(name), encoded.toHex())
        }
    }

    @Test
    fun recordsRoundTripAndPeekTheirDiscriminator() {
        for ((name, encoded) in vectors()) {
            assertNotNull("peekType failed for '$name'", SpoolCodec.peekType(encoded))
        }

        val sub = SpoolCodec.decode<SpoolSub>(vectors().getValue("sub"))!!
        assertEquals(1L, sub.q)
        assertArrayEquals(bytes(32, 1), sub.subs.single().scope)
        assertEquals(
            400,
            sub.subs
                .single()
                .bounds.maxFrames,
        )
        assertEquals(
            42L,
            sub.subs
                .single()
                .pow
                ?.n,
        )

        val list = SpoolCodec.decode<SpoolList>(vectors().getValue("listResponse"))!!
        assertEquals(2, list.blobIds?.size)
        assertArrayEquals(bytes(32, 3), list.blobIds?.first())
        assertArrayEquals(bytes(32, 5), list.tombstones?.single())

        val err = SpoolCodec.decode<SpoolErr>(vectors().getValue("errRate"))!!
        assertEquals(SpoolErrCode.RATE, err.code)
        assertEquals(30_000L, err.retryMs)
        assertNull(err.q)
    }

    @Test
    fun unknownDiscriminatorIsIdentifiableAndUnknownFieldsAreIgnored() {
        val future = SpoolCodec.encode(FutureRecord(t = "future", q = 9L, novel = "later"))

        assertEquals("future", SpoolCodec.peekType(future))
        assertNull(SpoolCodec.decode<SpoolPull>(future))

        val extendedOk = SpoolCodec.encode(FutureRecord(t = SpoolRecordType.OK, q = 9L, novel = "later"))
        val ok = SpoolCodec.decode<SpoolOk>(extendedOk)
        assertNotNull(ok)
        assertEquals(9L, ok!!.q)
    }

    @Test
    fun malformedBytesDecodeToNullNotThrow() {
        assertNull(SpoolCodec.peekType(byteArrayOf(0x42, 0x00)))
        assertNull(SpoolCodec.decode<SpoolHello>(ByteArray(0)))
        assertNull(SpoolCodec.decode<SpoolHello>(bytes(16, 7)))
    }

    private companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        val EXPECTED =
            mapOf(
                "helloSpool" to
                    "a561746568656c6c6f617601636d696e01666c696d697473a6676d6178426c6f621a" +
                    "00010000696d61785265636f72641a00020000696d617853636f7065731840676d61" +
                    "7850756c6c18406c6d61784672616d65734361701903e8686d617854746c4d731a24" +
                    "0c840067706f774269747314",
                "helloClient" to "a261746568656c6c6f617601",
                "sub" to
                    "a3617463737562617101647375627381a36573636f7065582001080f161d242b3239" +
                    "40474e555c636a71787f868d949ba2a9b0b7bec5ccd3da66626f756e6473a3696d61" +
                    "784672616d65731901906574746c4d731a0a4cb800676d6178426c6f621a00010000" +
                    "63706f77a2616e182a61641950c8",
                "digest" to
                    "a66174666469676573746573636f7065582001080f161d242b323940474e555c636a" +
                    "71787f868d949ba2a9b0b7bec5ccd3da6664696765737448020910171e252c336563" +
                    "6f756e74036466756c6cf466626f756e6473a3696d61784672616d65731901906574" +
                    "746c4d731a0a4cb800676d6178426c6f621a00010000",
                "listRequest" to
                    "a36174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da",
                "listResponse" to
                    "a56174646c6973746171026573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473825820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc5820040b12192027" +
                    "2e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dd6a746f6d6273746f" +
                    "6e6573815820050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2" +
                    "c9d0d7de",
                "pull" to
                    "a461746470756c6c6171036573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da67626c6f62496473815820030a11181f26" +
                    "2d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc",
                "blob" to
                    "a4617464626c6f626573636f7065582001080f161d242b323940474e555c636a7178" +
                    "7f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b4249" +
                    "50575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b2229" +
                    "30373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02091017" +
                    "1e252c333a41484f",
                "push" to
                    "a6617464707573686171046573636f7065582001080f161d242b323940474e555c63" +
                    "6a71787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d34" +
                    "3b424950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d14" +
                    "1b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb02" +
                    "0910171e252c333a41484f63706f77a2616e182a61641950c8",
                "event" to
                    "a46174656576656e746573636f7065582001080f161d242b323940474e555c636a71" +
                    "787f868d949ba2a9b0b7bec5ccd3da66626c6f6249645820030a11181f262d343b42" +
                    "4950575e656c737a81888f969da4abb2b9c0c7ced5dc64646174615830060d141b22" +
                    "2930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910" +
                    "171e252c333a41484f",
                "okBare" to "a26174626f6b617103",
                "okMissing" to
                    "a36174626f6b617103676d697373696e67815820040b121920272e353c434a51585f" +
                    "666d747b828990979ea5acb3bac1c8cfd6dd",
                "errScoped" to
                    "a461746365727264636f64656a746f6d6273746f6e65646171046573636f70655820" +
                    "01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3da",
                "errRate" to "a461746365727264636f64656472617465636d736769736c6f7720646f776e6772657472794d73197530",
            )
    }
}

/** A "later protocol version" record: a known/unknown `t` plus a field today's types lack. */
@Serializable
private class FutureRecord(
    val t: String,
    val q: Long,
    val novel: String,
)
