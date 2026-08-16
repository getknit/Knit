package app.getknit.knit.mesh.spool

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §7 record state machine: hello negotiation, `q` correlation, the per-connection subscription set,
 * and the three v1 behaviors that are easy to get wrong — the spool speaks first, `blob` carries no `q`,
 * and a scoped `err` is the SUB refusal rather than a correlated response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpoolConnectionTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()
        var closedWith: Pair<Int, String>? = null

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) {
            closedWith = code to reason
        }

        inline fun <reified T> last(): T = SpoolCodec.decode<T>(sent.last())!!
    }

    private val scope = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }

    private fun connection(
        link: SpoolLink,
        onDigest: suspend (SpoolDigest) -> Unit = {},
        onEvent: suspend (SpoolEvent) -> Unit = {},
        onScopeError: suspend (String?, String, Long?) -> Unit = { _, _, _ -> },
    ) = SpoolConnection("ws://spool/spool/v1", link, onDigest, onEvent, onScopeError)

    private fun serverHello(
        v: Int = SPOOL_RECORD_VERSION,
        min: Int = SPOOL_RECORD_VERSION,
        powBits: Int = 0,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = v,
            min = min,
            limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            powBits = powBits,
        ),
    )

    @Test
    fun `answers the spool's unprompted hello with nothing but the chosen version`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(powBits = 20))

            assertTrue(conn.awaitReady())
            assertEquals(1, link.sent.size)
            // Byte-identical to the spec §13 `helloClient` vector: {"t":"hello","v":1} and nothing else.
            assertEquals("a261746568656c6c6f617601", hex(link.sent.single()))
            assertEquals(20, conn.powBits)
            assertEquals(2, conn.limits?.maxPull)
        }

    @Test
    fun `closes 4002 and never sends a hello when the versions do not overlap`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(v = 99, min = 7))

            assertFalse(conn.awaitReady())
            assertEquals(SpoolCloseCode.VERSION to "no version overlap", link.closedWith)
            assertTrue(link.sent.isEmpty())
        }

    @Test
    fun `sub marks the scope subscribed and a scoped err un-marks it`() =
        runTest {
            val link = RecordingLink()
            var reported: Triple<String?, String, Long?>? = null
            val conn = connection(link, onScopeError = { s, c, r -> reported = Triple(s, c, r) })
            conn.onMessage(serverHello())

            conn.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))
            assertTrue(conn.isSubscribed(hex(scope)))

            // A SUB refusal carries the SUB's q, which correlates to nothing outstanding — it is routed as
            // a scope error, not silently dropped, and the scope must stop counting as subscribed.
            conn.onMessage(
                SpoolCodec.encode(SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.POW, q = 99, scope = scope)),
            )
            assertFalse(conn.isSubscribed(hex(scope)))
            assertEquals(Triple(hex(scope), SpoolErrCode.POW, null), reported)
        }

    @Test
    fun `correlates concurrent requests by q and attributes blobs to the pull, not a parallel list`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var listing: SpoolReply.Listing? = null
            var pulled: PullOutcome? = null
            launch { listing = conn.list(scope) }
            runCurrent()
            val listQ = link.last<SpoolList>().q
            launch { pulled = conn.pull(scope, listOf(other)) }
            runCurrent()
            val pullQ = link.last<SpoolPull>().q
            assertTrue("q must increase monotonically per connection", pullQ > listQ)

            // A BLOB carries no q: it must land in the in-flight PULL even though a LIST for the same
            // scope is outstanding, and the two terminal responses must not cross.
            conn.onMessage(SpoolCodec.encode(SpoolBlob(t = SpoolRecordType.BLOB, scope = scope, blobId = other, data = byteArrayOf(7))))
            conn.onMessage(SpoolCodec.encode(SpoolOk(t = SpoolRecordType.OK, q = pullQ, missing = listOf(scope))))
            conn.onMessage(
                SpoolCodec.encode(SpoolList(t = SpoolRecordType.LIST, q = listQ, scope = scope, blobIds = listOf(other))),
            )
            runCurrent()

            assertEquals(1, pulled?.blobs?.size)
            assertEquals(hex(other), pulled?.blobs?.single()?.let { hex(it.blobId) })
            assertEquals(listOf(hex(scope)), pulled?.missing?.map { hex(it) })
            assertEquals(listOf(hex(other)), listing?.blobIds?.map { hex(it) })
        }

    @Test
    fun `a dead socket fails every outstanding request instead of hanging the heal loop`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var reply: SpoolReply? = null
            launch { reply = conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            conn.onClosed()
            runCurrent()

            assertEquals(SpoolReply.Closed, reply)
            assertFalse(conn.isSubscribed(hex(scope)))
        }

    @Test
    fun `a request the spool never answers times out instead of wedging the heal loop`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())

            var reply: SpoolReply? = null
            launch { reply = conn.push(scope, other, byteArrayOf(1)) }
            runCurrent()
            assertNull("still waiting while the spool stays silent", reply)
            // A spool that accepts a record and answers nothing must not park this caller forever: the
            // next heal round re-derives the diff, and a re-PUSH is byte-identical anyway.
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(SpoolReply.Closed, reply)
        }

    @Test
    fun `refuses to emit a record larger than the spool's advertised maxRecord`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val before = link.sent.size

            val reply = conn.push(scope, other, ByteArray(8192))

            assertEquals(SpoolReply.Closed, reply)
            assertEquals("an oversize record must never reach the wire", before, link.sent.size)
        }

    @Test
    fun `ignores unknown record types and a post-negotiation hello rather than tearing down`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello())
            val after = link.sent.size

            conn.onMessage(SpoolCodec.encode(SpoolRecordHead(t = "x-future")))
            conn.onMessage(serverHello(powBits = 30))
            conn.onMessage(byteArrayOf(0xFF.toByte(), 0x00))

            assertNull(link.closedWith)
            assertEquals(after, link.sent.size)
            assertEquals("a second hello must not renegotiate", 0, conn.powBits)
        }
}
