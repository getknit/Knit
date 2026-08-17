package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.sha256Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The member-side attachment rules of `docs/SPOOL_PROTOCOL.md` §4.5/§9.5. Two properties carry the
 * weight here and are tested directly rather than through [ScopeSync]: chunk positions are derivable
 * from the attachment alone (so no manifest object exists to disagree about), and reassembly only ever
 * yields bytes that hash back to the address the frame named.
 */
class ScopeAttachmentsTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"
    private val root = ByteArray(32) { it.toByte() }
    private val groupId = "g-00112233445566778899aabb"

    private fun dmScope(peer: String = bob) =
        Scope(
            id = ScopeCrypto.dmScopeId(root, alice, peer),
            keys = ScopeCrypto.dmSealKeys(root, alice, peer),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            peerId = peer,
        )

    private fun groupScope() =
        Scope(
            id = ScopeCrypto.groupScopeId(root, groupId, 1),
            keys = ScopeCrypto.groupSealKeys(root, groupId, 1),
            bounds = ScopeRegistry.DEFAULT_BOUNDS,
            groupId = groupId,
            roster = setOf(alice, bob, carol),
        )

    private fun attachment(size: Int): Pair<ByteArray, String> {
        val bytes = ByteArray(size) { ((it * 7) and 0xFF).toByte() }
        return bytes to sha256Hex(bytes)
    }

    @Test
    fun `chunk count and slicing cover the attachment exactly, short only at the end`() {
        val chunk = ScopeCrypto.ATTACH_CHUNK_BYTES
        assertEquals(1, ScopeAttachments.chunkCount(1))
        assertEquals(1, ScopeAttachments.chunkCount(chunk))
        assertEquals(2, ScopeAttachments.chunkCount(chunk + 1))
        assertEquals(171, ScopeAttachments.MAX_CHUNKS)

        val (bytes, _) = attachment(chunk + 100)
        val first = ScopeAttachments.sliceAt(bytes, 0)
        val last = ScopeAttachments.sliceAt(bytes, 1)
        assertEquals(chunk, first.size)
        assertEquals(100, last.size)
        assertArrayEquals(bytes, first + last)
        assertThrows(IllegalArgumentException::class.java) { ScopeAttachments.sliceAt(bytes, 2) }
    }

    @Test
    fun `an attachment round-trips through seal, chunking and reassembly`() {
        val scope = dmScope()
        val (bytes, aHash) = attachment(ScopeCrypto.ATTACH_CHUNK_BYTES * 2 + 17)
        val total = ScopeAttachments.chunkCount(bytes.size)
        val hashBytes = ScopeAttachments.hashBytes(aHash)!!

        val assembly = ScopeAttachments.Assembly(aHash, total)
        for (index in 0 until total) {
            val sealed = ScopeCrypto.sealChunk(scope.keys, scope.id, hashBytes, index, total, ScopeAttachments.sliceAt(bytes, index))
            val opened = ScopeCrypto.openChunk(scope.keys, scope.id, sealed)
            assertArrayEquals(hashBytes, opened.aHash)
            assertEquals(total, opened.total)
            assertTrue(assembly.put(opened.index, opened.data))
        }

        assertTrue(assembly.isComplete())
        assertEquals(bytes.size, assembly.bytes)
        assertArrayEquals(bytes, assembly.finish())
    }

    @Test
    fun `a sealed chunk stays inside the default maxBlob`() {
        val scope = dmScope()
        val (bytes, aHash) = attachment(ScopeCrypto.ATTACH_CHUNK_BYTES)
        val sealed =
            ScopeCrypto.sealChunk(scope.keys, scope.id, ScopeAttachments.hashBytes(aHash)!!, 0, 1, ScopeAttachments.sliceAt(bytes, 0))

        // 1 version + 12 nonce + 40 header + 49152 data + 16 tag. The whole point of the 48 KiB choice.
        assertEquals(49221, sealed.size)
        assertTrue(sealed.size <= ScopeRegistry.DEFAULT_MAX_BLOB)
    }

    @Test
    fun `reassembly refuses bytes that do not hash to the named address`() {
        val (bytes, aHash) = attachment(500)
        val assembly = ScopeAttachments.Assembly(aHash, 1)
        assertTrue(assembly.put(0, bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertTrue(assembly.isComplete())
        assertNull(assembly.finish())
    }

    @Test
    fun `assembly refuses a short interior chunk, a duplicate, and an out-of-range index`() {
        val (bytes, aHash) = attachment(ScopeCrypto.ATTACH_CHUNK_BYTES + 10)
        val assembly = ScopeAttachments.Assembly(aHash, 2)

        // Only the final chunk may be short — a short interior one would shift every later offset.
        assertFalse(assembly.put(0, ByteArray(10)))
        assertFalse(assembly.put(2, ScopeAttachments.sliceAt(bytes, 1)))
        assertFalse(assembly.put(-1, ScopeAttachments.sliceAt(bytes, 1)))
        assertFalse(assembly.put(0, ByteArray(0)))
        assertFalse(assembly.put(0, ByteArray(ScopeCrypto.ATTACH_CHUNK_BYTES + 1)))

        assertTrue(assembly.put(0, ScopeAttachments.sliceAt(bytes, 0)))
        assertFalse(assembly.put(0, ScopeAttachments.sliceAt(bytes, 0)))
        assertEquals(setOf(0), assembly.held)
        assertNull(assembly.finish())
    }

    @Test
    fun `assembly bounds the peer-supplied total`() {
        val (_, aHash) = attachment(10)
        assertThrows(IllegalArgumentException::class.java) { ScopeAttachments.Assembly(aHash, 0) }
        assertThrows(IllegalArgumentException::class.java) { ScopeAttachments.Assembly(aHash, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            ScopeAttachments.Assembly(aHash, ScopeAttachments.MAX_CHUNKS + 1)
        }
    }

    @Test
    fun `the presence bitmap round-trips and reports what is missing`() {
        val bits = ScopeAttachments.bitmap(setOf(0, 3, 7, 8), total = 10)

        assertEquals(2, bits.size)
        for (index in 0 until 10) {
            assertEquals(index in setOf(0, 3, 7, 8), ScopeAttachments.bitSet(bits, index))
        }
        assertEquals(listOf(1, 2, 4, 5, 6, 9), ScopeAttachments.missing(bits, 10))
        assertEquals(emptyList<Int>(), ScopeAttachments.missing(ScopeAttachments.bitmap((0 until 10).toSet(), 10), 10))
        // Indices the sender never had, and reads past the end, are absent rather than a crash.
        assertFalse(ScopeAttachments.bitSet(bits, 99))
        assertFalse(ScopeAttachments.bitSet(bits, -1))
        assertArrayEquals(ByteArray(2), ScopeAttachments.bitmap(setOf(10, 99, -1), total = 10))
    }

    @Test
    fun `references reads the cleartext hash from frames the scope carries, and only those`() {
        val (_, aHash) = attachment(100)
        val (_, otherHash) = attachment(200)
        val frames =
            listOf(
                dmFrame("m1", from = alice, to = bob, sentAt = 1_000L, attachmentHash = aHash),
                dmFrame("m2", from = bob, to = alice, sentAt = 5_000L, attachmentHash = aHash),
                dmFrame("m3", from = alice, to = bob, sentAt = 2_000L),
                // Another pair's DM, and a group frame: neither belongs to this DM scope.
                dmFrame("m4", from = alice, to = carol, sentAt = 3_000L, attachmentHash = otherHash),
                groupChatFrame("g1", from = bob, groupId = groupId, members = listOf(alice, bob), attachmentHash = otherHash),
            )

        val refs = ScopeAttachments.references(frames, dmScope(), alice)

        assertEquals(listOf(aHash), refs.map { it.aHash })
        assertEquals("image/jpeg", refs.single().mime)
        // Deduped across re-serves, keeping the newest frame — that is the sentAt the DOA guard reads.
        assertEquals(5_000L, refs.single().sentAt)
    }

    @Test
    fun `references covers group scopes and skips malformed hashes`() {
        val (_, aHash) = attachment(100)
        val frames =
            listOf(
                groupChatFrame("g1", from = bob, groupId = groupId, members = listOf(alice, bob), attachmentHash = aHash),
                groupChatFrame("g2", from = carol, groupId = groupId, members = listOf(alice, bob), attachmentHash = "not-a-hash"),
                groupLeaveFrame("g3", from = bob, groupId = groupId),
                groupUpdateFrame("g4", from = bob, groupId = groupId, members = listOf(alice, bob)),
            )

        val refs = ScopeAttachments.references(frames, groupScope(), alice)

        assertEquals(listOf(aHash), refs.map { it.aHash })
    }

    @Test
    fun `hashBytes accepts only a well-formed content address`() {
        val (_, aHash) = attachment(100)

        assertEquals(ScopeCrypto.BLOB_ID_BYTES, ScopeAttachments.hashBytes(aHash)!!.size)
        assertEquals(aHash, hex(ScopeAttachments.hashBytes(aHash)!!))
        assertNull(ScopeAttachments.hashBytes(aHash.uppercase()))
        assertNull(ScopeAttachments.hashBytes(aHash.dropLast(1)))
        assertNull(ScopeAttachments.hashBytes(""))
    }
}
