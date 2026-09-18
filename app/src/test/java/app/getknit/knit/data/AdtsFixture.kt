package app.getknit.knit.data

/**
 * Builds a synthetic ADTS stream of [frames] frames at [sampleRateIndex] (7 = 22.05 kHz, the recorder's
 * rate), each [frameLength] bytes including its own 7-byte header. Payload bytes are left zero — the
 * [VoiceAudio] duration walk only ever reads headers, which is exactly the property that makes it
 * decoder-free, and what lets a pipeline test hand the receive side a "voice note" the host can time.
 */
fun adts(
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
