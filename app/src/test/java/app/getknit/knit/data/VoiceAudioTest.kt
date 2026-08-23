package app.getknit.knit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of [VoiceAudio] that carries the design weight and needs no device: the ADTS frame walk.
 *
 * It matters because it is the *only* reason a voice note costs no wire field. Duration could have been a
 * nullable field on `MessageContent` — instead both ends recompute it from the bytes, so a sender and a
 * recipient agree by construction rather than by trusting a number one of them sent. That only holds if the
 * walk is exact, which is what the arithmetic cases below pin, and if malformed input degrades quietly
 * rather than throwing, which is what the rest pin (an inbound handler must never throw — `rules/mesh.md`).
 *
 * [VoiceAudio.peaks] is deliberately not tested here: it needs a real platform decoder, and a Robolectric
 * shadow would only assert that a stub was called. Its behaviour is covered on-device.
 */
class VoiceAudioTest {
    /**
     * Builds a synthetic ADTS stream of [frames] frames at [sampleRateIndex] (7 = 22.05 kHz, the recorder's
     * rate), each [frameLength] bytes including its own 7-byte header. Payload bytes are left zero — the
     * walk only ever reads headers, which is exactly the property that makes it decoder-free.
     */
    private fun adts(
        frames: Int,
        frameLength: Int = 64,
        sampleRateIndex: Int = 7,
    ): ByteArray {
        val out = ByteArray(frames * frameLength)
        for (f in 0 until frames) {
            val o = f * frameLength
            out[o] = 0xFF.toByte() // syncword high
            out[o + 1] = 0xF1.toByte() // syncword low + MPEG-4, no CRC
            out[o + 2] = (((sampleRateIndex and 0x0F) shl 2) or 0x40).toByte() // profile + rate index
            out[o + 3] = ((frameLength shr 11) and 0x03).toByte()
            out[o + 4] = ((frameLength shr 3) and 0xFF).toByte()
            out[o + 5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            out[o + 6] = 0xFC.toByte()
        }
        return out
    }

    @Test
    fun `duration is frames times 1024 samples over the sample rate`() {
        // 216 frames x 1024 samples / 22050 Hz = 10032 ms. Chosen to land off a round number so an
        // implementation that guessed from byte count rather than frames couldn't coincidentally match.
        assertEquals(216 * 1024 * 1000L / 22050L, VoiceAudio.durationMs(adts(216))?.toLong())
    }

    @Test
    fun `duration follows the sample rate, not the byte count`() {
        // Same frames, same bytes, different declared rate: a faster rate plays the same frames in less
        // time. This is the case a byte-length heuristic gets wrong.
        val at22k = VoiceAudio.durationMs(adts(100, sampleRateIndex = 7))!!
        val at44k = VoiceAudio.durationMs(adts(100, sampleRateIndex = 4))!!
        // Integer truncation can differ by a millisecond, hence the delta rather than an exact match.
        assertEquals(at22k.toDouble(), (at44k * 2).toDouble(), 1.0)
    }

    @Test
    fun `frame length is read from all thirteen of its bits`() {
        // 1200 bytes needs bits above the byte-4 window, so a parser that dropped the two high bits from
        // byte 3 would walk to the wrong offset, lose the syncword, and stop after one frame.
        val stream = adts(frames = 8, frameLength = 1200)
        assertEquals(8 * 1024 * 1000L / 22050L, VoiceAudio.durationMs(stream)?.toLong())
    }

    @Test
    fun `a truncated stream reports the duration of the frames that are intact`() {
        // A transfer cut mid-frame is the realistic corruption. The last, partial frame is not counted —
        // reporting a shorter, true duration beats reporting none, and beats over-counting a frame whose
        // bytes never arrived.
        val whole = adts(frames = 10)
        val cut = whole.copyOf(whole.size - 20)
        assertEquals(9 * 1024 * 1000L / 22050L, VoiceAudio.durationMs(cut)?.toLong())
    }

    @Test
    fun `bytes that are not ADTS yield null rather than throwing`() {
        assertNull(VoiceAudio.durationMs(ByteArray(0)))
        assertNull(VoiceAudio.durationMs(ByteArray(3) { 0xFF.toByte() })) // shorter than a header
        assertNull(VoiceAudio.durationMs(ByteArray(64))) // all zero: no syncword
        assertNull(VoiceAudio.durationMs("not audio at all, just text".toByteArray()))
    }

    @Test
    fun `a reserved sample-rate index yields null rather than a bogus duration`() {
        // Index 13-15 are reserved. Guessing a rate here would silently mis-time every such stream.
        assertNull(VoiceAudio.durationMs(adts(frames = 4, sampleRateIndex = 13)))
    }

    @Test
    fun `a frame claiming to be shorter than its own header ends the walk`() {
        // The one malformed input that would *hang* rather than merely mislead: a declared length of 0 or 3
        // never advances the offset, so an unguarded walk spins forever on it. Built as a real, full-size
        // frame whose header lies about its length, which is what a corrupted byte actually produces.
        val stream = adts(frames = 4)
        stream[3] = 0
        stream[4] = 0
        stream[5] = (0x03 shl 5).toByte() // declares length 3, shorter than the 7-byte header
        assertNull(VoiceAudio.durationMs(stream))
    }

    @Test
    fun `a frame claiming to run past the end of the buffer ends the walk`() {
        // The mirror case, and the one that would read out of bounds if the walk trusted the header.
        val stream = adts(frames = 4)
        val huge = 8000
        stream[3] = ((huge shr 11) and 0x03).toByte()
        stream[4] = ((huge shr 3) and 0xFF).toByte()
        stream[5] = (((huge and 0x07) shl 5) or 0x1F).toByte()
        assertNull(VoiceAudio.durationMs(stream))
    }

    @Test
    fun `one loud transient does not flatten the rest of the waveform`() {
        // The case that made a real recording render as a single spike and a row of dots: a knock on the
        // phone is several times louder than speech, so dividing by the true peak scales every syllable to
        // nothing. Normalising to a percentile clips the transient — it is one bar, already full height —
        // and gives the envelope the space back.
        val speech = LongArray(VoiceAudio.PEAK_COUNT) { 1_000L }
        speech[0] = 20_000L // the knock
        val scale = VoiceAudio.scaleFor(speech)
        assertTrue("a lone transient must not set the scale", scale < 20_000L)
        assertTrue("and the speech bars must land in the visible upper range", 1_000L * 255 / scale > 120)
    }

    @Test
    fun `silence is not amplified into a waveform that was never there`() {
        // The mirror failure, and why the divisor has a floor: scaling purely by a percentile would take a
        // silent room's noise floor to full height and draw a confident envelope of nothing.
        val silence = LongArray(VoiceAudio.PEAK_COUNT) { 4L }
        silence[3] = 900L // one faint click in an otherwise dead room
        val scale = VoiceAudio.scaleFor(silence)
        assertEquals("a dead room scales by its true peak, not its noise floor", 900L, scale)
        assertTrue("so the noise floor still draws as quiet", 4L * 255 / scale < 30)
    }

    @Test
    fun `an ordinary speech envelope normalises to roughly full height`() {
        // The common case must be unaffected by both rules above: when the loudest bar IS speech, the
        // waveform should still reach the top.
        val envelope = LongArray(VoiceAudio.PEAK_COUNT) { i -> 3_000L + (i % 8) * 500L }
        val scale = VoiceAudio.scaleFor(envelope)
        val loudest = envelope.max()
        assertTrue("the peak still renders at or near full height", loudest * 255 / scale >= 240)
    }

    @Test
    fun `peaks round-trip through the stored column as normalised bar heights`() {
        val bars = byteArrayOf(0, 64.toByte(), 128.toByte(), 255.toByte())
        val decoded = VoiceAudio.decodePeaks(VoiceAudio.encodePeaks(bars))
        assertNotNull(decoded)
        assertEquals(4, decoded!!.size)
        assertEquals(0f, decoded[0], 0.001f)
        // 255 is full height: the loudest bar always reaches the top, so a quiet note still draws a
        // readable waveform rather than a flat line.
        assertEquals(1f, decoded[3], 0.001f)
        // A byte past 127 must survive as a *large* value; reading it as a signed Kotlin Byte would make
        // the loudest samples render as the quietest.
        assertTrue(decoded[2] > decoded[1])
    }

    @Test
    fun `a missing or malformed peaks column decodes to null`() {
        assertNull(VoiceAudio.decodePeaks(null))
        assertNull(VoiceAudio.decodePeaks(""))
        assertNull(VoiceAudio.decodePeaks("not base64 !!!"))
    }

    @Test
    fun `isVoice recognises audio and nothing else`() {
        assertTrue(VoiceAudio.isVoice(VoiceAudio.MIME))
        assertTrue(VoiceAudio.isVoice("audio/mp4"))
        assertFalse(VoiceAudio.isVoice("image/jpeg"))
        assertFalse(VoiceAudio.isVoice("image/webp"))
        assertFalse(VoiceAudio.isVoice(null))
    }
}
