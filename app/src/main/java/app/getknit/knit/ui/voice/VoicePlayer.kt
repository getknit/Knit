package app.getknit.knit.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import app.getknit.knit.data.ByteArrayMediaSource
import app.getknit.knit.data.blob.BlobDao
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.b64d
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Plays voice notes, one at a time, from the encrypted blob store.
 *
 * **One player, app-wide.** Voice notes are played from chat bubbles, and any number of them can be on
 * screen — so playback state has to live somewhere that can arbitrate. Starting a second note stops the
 * first, exactly as a single `MediaPlayer` naturally enforces, and [nowPlaying] is what each bubble observes
 * to decide whether it is the one drawing a pause button.
 *
 * Bytes reach the player through [ByteArrayMediaSource], never a file: an attachment is ciphertext in the
 * `blobs` table, and decrypting it to `cacheDir` so a player could open a path would put plaintext audio on
 * disk — the invariant ADR 029 pinned. This is the audio half of what `BlobFetcher` does for images.
 */
class VoicePlayer(
    context: Context,
    private val blobs: BlobDao,
) {
    /** The playback state one bubble needs: which note, how far in, and whether it is running. */
    data class Playback(
        val hash: String,
        val positionMs: Int,
        val durationMs: Int,
        val playing: Boolean,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    // The player outlives any one chat screen (that is the point of it being app-wide), so it owns its scope
    // rather than borrowing a ViewModel's — a note started in one thread must not be cancelled by navigating
    // away from it, only by the next note or an explicit stop. Main.immediate keeps MediaPlayer calls on the
    // thread it was created on, which is what its state machine expects.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _nowPlaying = MutableStateFlow<Playback?>(null)

    /** The note currently loaded, or null when nothing is. Bubbles compare [Playback.hash] against their own. */
    val nowPlaying: StateFlow<Playback?> = _nowPlaying.asStateFlow()

    // Guards player/ticker/focus as a set: play() suspends on a DB read and a decrypt, so two taps in quick
    // succession on different bubbles would otherwise interleave and leave a player running with no state.
    private val lock = Mutex()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            // Losing focus to a call, a navigation prompt or another player pauses rather than stops, so the
            // scrub position survives and the user can resume where they were.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                scope.launch { pause() }
            }
        }

    /**
     * Loads and plays the voice note stored under [hash], decrypting with [key] when the attachment is
     * end-to-end encrypted (null only for a plaintext broadcast-room attachment). Tapping the note that is
     * already playing pauses it; tapping a paused one resumes.
     */
    fun play(
        hash: String,
        key: String?,
    ) {
        scope.launch {
            lock.withLock {
                val current = _nowPlaying.value
                if (current?.hash == hash && player != null) {
                    if (current.playing) pauseLocked() else resumeLocked()
                    return@withLock
                }
                stopLocked()
                val bytes = bytesFor(hash, key) ?: return@withLock
                startLocked(hash, bytes)
            }
        }
    }

    /** Seeks the loaded note to [positionMs]. Ignored when [hash] is not the note that is loaded. */
    fun seek(
        hash: String,
        positionMs: Int,
    ) {
        scope.launch {
            lock.withLock {
                val p = player ?: return@withLock
                if (_nowPlaying.value?.hash != hash) return@withLock
                runCatching { p.seekTo(positionMs.coerceAtLeast(0)) }
                _nowPlaying.value = _nowPlaying.value?.copy(positionMs = positionMs)
            }
        }
    }

    /** Pauses playback, keeping the position so the next tap resumes. */
    fun pause() {
        scope.launch { lock.withLock { pauseLocked() } }
    }

    /** Stops and unloads whatever is playing. Called when the owning screen goes away. */
    fun stop() {
        scope.launch { lock.withLock { stopLocked() } }
    }

    private suspend fun bytesFor(
        hash: String,
        key: String?,
    ): ByteArray? {
        val raw = blobs.bytes(hash) ?: return null
        if (key == null) return raw
        return withContext(Dispatchers.Default) { AttachmentCrypto.open(raw, b64d(key)) }
    }

    private fun startLocked(
        hash: String,
        bytes: ByteArray,
    ) {
        if (!requestFocus()) return
        val p = MediaPlayer()
        val ok =
            runCatching {
                p.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                p.setDataSource(ByteArrayMediaSource(bytes))
                // Voice notes are seconds long and already in memory, so preparing synchronously costs a few
                // milliseconds and saves an async state machine that would have to survive a second tap.
                p.prepare()
                p.start()
            }.isSuccess
        if (!ok) {
            Log.w(TAG, "voice playback failed to start")
            runCatching { p.release() }
            abandonFocus()
            return
        }
        p.setOnCompletionListener {
            scope.launch { lock.withLock { stopLocked() } }
        }
        player = p
        _nowPlaying.value = Playback(hash, positionMs = 0, durationMs = p.duration.coerceAtLeast(0), playing = true)
        startTicker()
    }

    private fun resumeLocked() {
        val p = player ?: return
        if (!requestFocus()) return
        runCatching { p.start() }
        _nowPlaying.value = _nowPlaying.value?.copy(playing = true)
        startTicker()
    }

    private fun pauseLocked() {
        val p = player ?: return
        runCatching { p.pause() }
        ticker?.cancel()
        ticker = null
        _nowPlaying.value = _nowPlaying.value?.copy(playing = false)
        abandonFocus()
    }

    private fun stopLocked() {
        ticker?.cancel()
        ticker = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _nowPlaying.value = null
        abandonFocus()
    }

    /** Drives the scrubber. Polling beats a listener because `MediaPlayer` offers no position callback. */
    private fun startTicker() {
        ticker?.cancel()
        ticker =
            scope.launch {
                while (true) {
                    delay(TICK_MS)
                    lock.withLock {
                        val p = player ?: return@withLock
                        val playing = runCatching { p.isPlaying }.getOrDefault(false)
                        val position = runCatching { p.currentPosition }.getOrDefault(0)
                        _nowPlaying.value = _nowPlaying.value?.copy(positionMs = position, playing = playing)
                    }
                }
            }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener(focusListener)
                .build()
        focusRequest = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private companion object {
        /** Scrubber refresh. Fast enough to look continuous, slow enough not to recompose every frame. */
        const val TICK_MS = 60L

        const val TAG = "VoicePlayer"
    }
}
