package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The read-modify-write helper behind ADR 045's config writes. Its whole reason to exist is the last test
 * here: Meshtastic's `set_config` assigns the entire sub-config, so anything this drops is something the
 * board silently loses.
 */
class ProtoSpliceTest {
    private fun hex(s: String): ByteArray =
        s
            .split(" ")
            .filter { it.isNotEmpty() }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it) }

    @Test
    fun replacesAnExistingFieldInPlace() {
        val raw = ProtoWriter().varint(1, 2).varint(7, 900).toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(21_600L, readVarintField(out, 7))
        assertEquals(2L, readVarintField(out, 1))
    }

    @Test
    fun appendsAFieldTheBoardNeverSent() {
        val raw = ProtoWriter().varint(1, 2).toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(21_600L, readVarintField(out, 7))
    }

    @Test
    fun aZeroClearsTheFieldRatherThanWritingIt() {
        // proto3: absent *is* the default, so clearing a bool means emitting nothing for it.
        val raw = ProtoWriter().varint(1, 900).varint(2, 1).toByteArray()
        val out = spliceVarintFields(raw, mapOf(2 to 0L))!!
        assertNull(readVarintField(out, 2))
        assertEquals(900L, readVarintField(out, 1))
    }

    @Test
    fun malformedInputSplicesToNullRatherThanToGarbage() {
        assertNull(spliceVarintFields(hex("0A 7F"), mapOf(1 to 1L))) // a length past the end
        assertNull(readVarintField(hex("0A 7F"), 1))
    }

    @Test
    fun readsTheLastOccurrenceAndIgnoresAWrongWireType() {
        assertEquals(2L, readVarintField(ProtoWriter().varint(1, 1).varint(1, 2).toByteArray(), 1))
        assertNull("a length-delimited field 1 is not a varint", readVarintField(ProtoWriter().string(1, "x").toByteArray(), 1))
    }

    @Test
    fun everyFieldThisCodecDoesNotModelSurvivesByteForByte() {
        val raw =
            ProtoWriter()
                .varint(1, 2) // role
                .varint(6, 3) // rebroadcast_mode
                .varint(7, 900) // node_info_broadcast_secs — the one field we mean to change
                .string(11, "Europe/Berlin") // tzdef: a string this codec has no idea about
                .fixed32(99, 0xDEADBEEFu) // and a wire type it never writes
                .toByteArray()
        val out = spliceVarintFields(raw, mapOf(7 to 21_600L))!!
        assertEquals(2L, readVarintField(out, 1))
        assertEquals(3L, readVarintField(out, 6))
        assertEquals(21_600L, readVarintField(out, 7))
        val reader = ProtoReader(out)
        val kept = mutableListOf<String>()
        while (reader.hasMore) {
            val tag = reader.readTag()
            when (tag ushr WireType.FIELD_SHIFT) {
                11 -> kept += reader.readString()
                99 -> kept += reader.readFixed32().toString(16).uppercase()
                else -> reader.skip(tag and WireType.MASK)
            }
        }
        assertEquals(listOf("Europe/Berlin", "DEADBEEF"), kept)
    }
}
