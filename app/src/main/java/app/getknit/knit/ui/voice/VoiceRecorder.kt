package app.getknit.knit.ui.voice

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import app.getknit.knit.data.VoiceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Records a voice note straight into memory.
 *
 * `MediaRecorder` insists on a sink it can write to, and the obvious sink — a file in `cacheDir` — is the
 * one thing ADR 029 forbids: attachment bytes must never exist as plaintext on disk, which is why the in-app
 * camera hands its capture over as a [ByteArray] instead of staging a JPEG. So the sink here is one end of a
 * [ParcelFileDescriptor.createPipe], drained on a background coroutine into a [ByteArrayOutputStream].
 *
 * That is what forces the output format. A pipe is not seekable, and `MediaRecorder`'s MPEG-4 muxer must
 * rewind to write its `moov` atom, so `.m4a` cannot be produced this way at all.
 * [MediaRecorder.OutputFormat.AAC_ADTS] is a pure stream with a self-describing header on every frame, so it
 * writes to a pipe happily. Two further properties of ADTS earn their keep on the failure paths below:
 * `VoiceAudio.durationMs` can measure the capture without a decoder, and **a truncated ADTS stream is still
 * a valid one** — every frame stands alone — so bytes that reached us before a failed [stop] are playable
 * rather than garbage.
 *
 * Recording holds the microphone, an exclusive system resource. Every exit path — [stop], [cancel], a
 * duration cap, a mid-recording failure — must release it, which is why [release] is idempotent and why the
 * owning ViewModel calls [cancel] from `onCleared`.
 *
 * Not thread-safe: drive it from a single scope (the ViewModel's).
 */
class VoiceRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var recorder: MediaRecorder? = null
    private var readSide: ParcelFileDescriptor? = null
    private var writeSide: ParcelFileDescriptor? = null
    private var drain: Job? = null
    private var sink: ByteArrayOutputStream? = null
    private var startedAt = 0L

    /** True between a successful [start] and the [stop]/[cancel] that ends it. */
    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since [start], or 0 when not recording — the source of the composer's elapsed counter. */
    fun elapsedMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    /**
     * Loudest sample since the previous call, normalised to 0f..1f, for the live level meter. Transient UI
     * only — the *stored* waveform is derived from the finished bytes by `VoiceAudio.peaks`, so the meter can
     * never disagree with the bars the bubble ends up drawing.
     */
    fun amplitude(): Float {
        val raw = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (raw / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    /**
     * Begins recording, returning false if the microphone could not be opened — already in use by a call or
     * another app, or a device without one. The caller has already cleared the `RECORD_AUDIO` gate.
     *
     * Tries [MediaRecorder.AudioSource.VOICE_RECOGNITION] first and falls back to `MIC`. The former is the
     * better source for speech — the platform applies no AGC/noise suppression tuned for hands-free calling,
     * which pumps audibly on ordinary talking — but it is a *hint* the HAL may decline, and a device that
     * declines it would otherwise open a recorder that captures nothing.
     */
    fun start(): Boolean {
        if (recorder != null) return false
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return false
        val read = pipe[0]
        val write = pipe[1]
        val out = ByteArrayOutputStream()
        val job = drainInto(out, read)

        val rate = encoderSampleRate()
        for (source in AUDIO_SOURCES) {
            val rec = newRecorder()
            if (configureAndStart(rec, source, rate, write)) {
                Log.d(TAG, "voice recording started source=$source rate=$rate")
                recorder = rec
                readSide = read
                writeSide = write
                drain = job
                sink = out
                startedAt = System.currentTimeMillis()
                return true
            }
            runCatching { rec.release() }
        }

        Log.w(TAG, "voice recorder failed to start on every audio source")
        job.cancel()
        runCatching { write.close() }
        runCatching { read.close() }
        return false
    }

    /**
     * Stops recording and returns the captured ADTS bytes, or null when nothing usable was captured.
     *
     * Two things here are load-bearing. First, closing the write side is what ends the drain — the reader
     * sees EOF and the coroutine completes — which is why this joins it before reading the buffer; without
     * the join the tail of the recording races the read and the clip is silently cut short.
     *
     * Second, **a failed `MediaRecorder.stop()` does not discard the recording.** `stop()` throws a bare
     * `RuntimeException` when the encoder produced no valid data, but it also throws on some devices while
     * having produced perfectly good data, and the two are indistinguishable from the exception. Since ADTS
     * frames are self-contained, whatever reached the pipe is playable regardless — so the bytes are judged
     * on their own terms (do they parse, and are they long enough?) rather than on whether `stop()` was
     * happy. The caller's minimum-length check is the real gate; see [ChatViewModel].
     */
    suspend fun stop(): ByteArray? {
        val out = sink ?: return null
        runCatching { recorder?.stop() }
            .onFailure {
                // Expected on a very short capture, and seen on some devices even after a good one. Not an
                // error by itself — the byte check below decides.
                Log.w(TAG, "voice recorder stop() threw (${it.javaClass.simpleName}); judging the bytes instead")
            }
        release()
        drain?.join()
        drain = null
        val bytes = withContext(Dispatchers.IO) { out.toByteArray() }
        sink = null
        startedAt = 0L
        if (bytes.isEmpty()) {
            Log.w(TAG, "voice recording produced no bytes")
            return null
        }
        val durationMs = VoiceAudio.durationMs(bytes)
        if (durationMs == null) {
            Log.w(TAG, "voice recording produced ${bytes.size} bytes that are not ADTS")
            return null
        }
        Log.d(TAG, "voice recording captured ${bytes.size} bytes / ${durationMs}ms")
        return bytes
    }

    /** Abandons the recording and discards its bytes. Safe to call when not recording. */
    fun cancel() {
        // reset() rather than stop(): stop() on a capture too short to have encoded a frame throws, and
        // there is nothing here worth throwing about — the bytes are being dropped either way.
        runCatching { recorder?.reset() }
        release()
        drain?.cancel()
        drain = null
        sink = null
        startedAt = 0L
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    /**
     * Configures [rec] for [source] and starts it, returning whether that worked. The sample rate is the
     * device's own answer, not ours: an encoder handed a rate it does not support can accept the
     * configuration and then emit nothing at all, which surfaces much later as a `stop()` that throws and a
     * recording that was never there. Asking [encoderSampleRate] up front turns that into a decision.
     */
    private fun configureAndStart(
        rec: MediaRecorder,
        source: Int,
        sampleRate: Int,
        write: ParcelFileDescriptor,
    ): Boolean =
        runCatching {
            rec.setAudioSource(source)
            rec.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(CHANNELS)
            rec.setAudioSamplingRate(sampleRate)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setOutputFile(write.fileDescriptor)
            // An encoder that dies mid-recording is otherwise completely silent until stop() throws.
            rec.setOnErrorListener { _, what, extra -> Log.w(TAG, "voice recorder error what=$what extra=$extra") }
            rec.prepare()
            rec.start()
        }.onFailure {
            Log.w(TAG, "voice recorder source=$source rate=$sampleRate failed: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess

    /**
     * The first of [SAMPLE_RATES] the device's own AAC encoder advertises, or the last as a blind fallback.
     * Speech-first ordering: 16 kHz is plenty for a voice note, is the native rate of the
     * `VOICE_RECOGNITION` source, and keeps a five-minute note small enough to cross BLE.
     */
    private fun encoderSampleRate(): Int {
        val supported =
            runCatching {
                val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecs.codecInfos
                    .filter { it.isEncoder && MediaFormat.MIMETYPE_AUDIO_AAC in it.supportedTypes }
                    .firstNotNullOfOrNull { info ->
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities
                    }
            }.getOrNull() ?: return SAMPLE_RATES.first()
        return SAMPLE_RATES.firstOrNull { supported.isSampleRateSupported(it) } ?: SAMPLE_RATES.last()
    }

    /**
     * Drains the pipe's read side into [out] continuously. It has to run for the whole recording: a pipe
     * holds only a page or so, and a full one blocks the recorder's own writer thread, which stalls and then
     * fails the recording itself.
     *
     * (Suppressed for multiple jumps deliberately: EOF and the byte cap are different ends to a recording,
     * and which one hit decides whether the caller gets the whole thing.)
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun drainInto(
        out: ByteArrayOutputStream,
        read: ParcelFileDescriptor,
    ): Job =
        scope.launch(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                    val buffer = ByteArray(DRAIN_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        // Bound the capture so a forgotten recording can't grow without limit. The
                        // ViewModel also stops at MAX_DURATION_MS; this is the byte-side backstop.
                        if (out.size() + n > MAX_BYTES) break
                        out.write(buffer, 0, n)
                    }
                }
            }.onFailure { Log.w(TAG, "voice pipe drain ended: ${it.javaClass.simpleName}") }
        }

    /**
     * Releases the microphone and both pipe ends. Idempotent, and never throws: it runs on the failure paths
     * of the very calls that might have thrown, so a second failure here must not mask the first.
     */
    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        // Closing the write side signals EOF to the drain; the read side is owned by AutoCloseInputStream.
        runCatching { writeSide?.close() }
        writeSide = null
        readSide = null
    }

    companion object {
        /** Longest voice note the composer will record. Well inside the 8 MiB attachment cap at [BIT_RATE]. */
        const val MAX_DURATION_MS = 5 * 60 * 1000L

        /**
         * Byte backstop on one recording. Generous against [MAX_DURATION_MS] at [BIT_RATE] so it only ever
         * catches a runaway encoder, never a legitimate long note.
         */
        const val MAX_BYTES = 4 * 1024 * 1024

        // VOICE_RECOGNITION first (no AGC/noise suppression tuned for hands-free calling, which pumps on
        // ordinary speech), MIC as the universally-available fallback.
        private val AUDIO_SOURCES =
            intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).toList()

        /** Preferred sample rates, best-for-speech first. See [encoderSampleRate]. */
        private val SAMPLE_RATES = listOf(16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

        private const val CHANNELS = 1
        private const val BIT_RATE = 32_000

        /** `getMaxAmplitude` is a 16-bit PCM magnitude, so this is its ceiling. */
        private const val MAX_AMPLITUDE = 32_767f

        private const val DRAIN_BUFFER_BYTES = 8 * 1024

        private const val TAG = "VoiceRecorder"
    }
}
