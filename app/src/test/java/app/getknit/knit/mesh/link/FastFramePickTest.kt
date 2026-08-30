package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [FastFramePick] — the per-peer framing choice the Wi-Fi Aware fast path makes (ADR 030 / ADR 060). */
class FastFramePickTest {
    private val legacy = listOf(byteArrayOf(0x01, 1, 2, 3))
    private val compact = listOf(byteArrayOf(0x03, 0, 0, 9))
    private val compactFrag = listOf(byteArrayOf(0x04, 0, 1, 0x02, 0x03, 0, 0), byteArrayOf(0x04, 0, 1, 0x12, 9))
    private val transcoded = listOf(byteArrayOf(0x05, 0, 0, 7))
    private val transcodedFrag = listOf(byteArrayOf(0x04, 0, 2, 0x02, 0x05, 0, 0), byteArrayOf(0x04, 0, 2, 0x12, 7))

    private val both = Protocol.CAP_FRAME_TRANSCODE or Protocol.CAP_FAST_COMPACT

    @Test
    fun aTranscodeCapablePeerGetsTheRichestFormThatCarriesTheFrame() {
        val pick = checkNotNull(FastFramePick.choose(both, { transcoded }, { compact }, { legacy }))
        assertSame(transcoded, pick.messages)
        assertEquals(FastFramePick.Form.TRANSCODED, pick.form)
        assertEquals(FastFramePick.Form.COMPACT, FastFramePick.choose(both, { null }, { compact }, { legacy })!!.form)
        assertEquals(FastFramePick.Form.LEGACY, FastFramePick.choose(both, { null }, { null }, { legacy })!!.form)
        assertNull("nothing fits: too big", FastFramePick.choose(both, { null }, { null }, { null }))
        // "Smaller wins" may hand a transcode-capable peer a 0x03 frame; the form follows the bytes, not the bit.
        assertEquals(FastFramePick.Form.COMPACT, FastFramePick.choose(both, { compact }, { compact }, { legacy })!!.form)
    }

    @Test
    fun aPeerWithoutTheBitIsNeverSentTheFormAndTheEncodingIsNeverBuilt() {
        var transcodeAsked = false
        val transcodedProbe: () -> List<ByteArray>? = {
            transcodeAsked = true
            transcoded
        }
        val compactPeer = checkNotNull(FastFramePick.choose(Protocol.CAP_FAST_COMPACT, transcodedProbe, { compact }) { legacy })
        assertEquals(FastFramePick.Form.COMPACT, compactPeer.form)
        assertFalse("the transcoded encoding is lazy: a compact-only peer never forces it", transcodeAsked)
        val cueOnly = checkNotNull(FastFramePick.choose(0L, transcodedProbe, { compact }) { legacy })
        assertEquals(FastFramePick.Form.LEGACY, cueOnly.form)
        assertFalse(transcodeAsked)
        assertSame(legacy, cueOnly.messages)
    }

    @Test
    fun theFormIsReadOffTheFirstMessageEvenWhenFragmented() {
        assertEquals(FastFramePick.Form.TRANSCODED, FastFramePick.formOf(transcodedFrag))
        assertEquals(FastFramePick.Form.COMPACT, FastFramePick.formOf(compactFrag))
        assertEquals(FastFramePick.Form.LEGACY, FastFramePick.formOf(legacy))
    }

    @Test
    fun recordCountsEachFormOnceAndFragmentedSendsBesides() {
        val metrics = MeshMetrics()
        FastFramePick.record(FastFramePick.Choice(legacy, FastFramePick.Form.LEGACY), metrics)
        FastFramePick.record(FastFramePick.Choice(compact, FastFramePick.Form.COMPACT), metrics)
        FastFramePick.record(FastFramePick.Choice(compactFrag, FastFramePick.Form.COMPACT), metrics)
        FastFramePick.record(FastFramePick.Choice(transcoded, FastFramePick.Form.TRANSCODED), metrics)
        FastFramePick.record(FastFramePick.Choice(transcodedFrag, FastFramePick.Form.TRANSCODED), metrics)
        val snap = metrics.snapshot()
        assertEquals(1L, snap.fastLegacySent)
        assertEquals("a single compact message", 1L, snap.fastCompactSent)
        assertEquals("both fragmented sends", 2L, snap.fastFragSent)
        assertEquals("transcoded, fragmented or not", 2L, snap.fastTranscodedSent)
    }
}
