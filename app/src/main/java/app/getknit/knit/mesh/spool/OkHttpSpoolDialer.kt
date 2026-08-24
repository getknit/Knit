package app.getknit.knit.mesh.spool

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The one place in the app that speaks OkHttp (`rules/mesh.md`): it turns a spool URL into the
 * [SpoolSocket] seam the pure record layer consumes. Everything protocol-shaped — hello, `q`, the heal
 * loop — lives above this in [SpoolConnection]/[ScopeSync] and is unit-tested without a socket.
 *
 * [allowCleartext] gates plain `ws://`. Release builds pass false, so a release APK cannot be pointed at
 * a plaintext relay however its settings are edited; debug builds pass true so a LAN daemon (which
 * terminates no TLS of its own — that is a reverse-proxy job) can be driven straight from the bench.
 */
class OkHttpSpoolDialer(
    private val allowCleartext: Boolean,
    private val client: OkHttpClient = defaultClient(),
) : SpoolDialer {
    override suspend fun dial(url: String): SpoolSocket? {
        if (!SpoolUrl.isAcceptable(url, allowCleartext)) {
            Log.w(TAG, "refusing spool url (scheme not allowed): ${SpoolUrl.redact(url)}")
            return null
        }
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
        if (request == null) {
            Log.w(TAG, "unparseable spool url: ${SpoolUrl.redact(url)}")
            return null
        }
        // Bounded rather than unlimited: a hostile spool must not be able to grow our heap by talking
        // faster than the reader drains. A dropped record costs one request timeout, and §9.1's heal
        // loop is the recovery path for exactly this kind of loss.
        val channel = Channel<ByteArray>(INBOX_CAPACITY)
        val socket = OkHttpSpoolSocket(channel)
        socket.attach(client.newWebSocket(request, socket.listener))
        return socket
    }

    private class OkHttpSpoolSocket(
        private val channel: Channel<ByteArray>,
    ) : SpoolSocket {
        @Volatile
        private var socket: WebSocket? = null

        @Volatile
        override var closeReason: String? = null
            private set

        @Volatile
        override var retryAfterMs: Long? = null
            private set

        override val incoming: ReceiveChannel<ByteArray> get() = channel

        val listener =
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    // Records are binary by definition (§7.1); a text frame is not one of ours.
                    // Cap before the copy: OkHttp has already buffered the message, but nothing above
                    // this line bounds one, so an arbitrarily large "record" would be materialized into
                    // our heap before the record layer ever saw a discriminator.
                    if (bytes.size > MAX_INBOUND_RECORD) {
                        Log.w(TAG, "oversize spool record dropped (${bytes.size} B) — the heal loop will recover")
                        return
                    }
                    if (channel.trySend(bytes.toByteArray()).isFailure) {
                        Log.w(TAG, "spool inbox full, dropped a record — the heal loop will recover")
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    Log.i(TAG, "spool closing: $code $reason")
                    closeReason = "close $code${if (reason.isBlank()) "" else " $reason"}"
                    channel.close()
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    channel.close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    Log.i(TAG, "spool socket failed: ${t.javaClass.simpleName} ${response?.code ?: ""}")
                    // A refused upgrade arrives as an HTTP status, never a close code: §7.1 defines only
                    // four and none of them means "come back later" (4003 would accuse a client that did
                    // nothing wrong). OkHttp reports the refusal as a bare ProtocolException, so without
                    // the status a spool at capacity and a spool that is simply broken reach the UI as
                    // the same word, and they want opposite reactions from the user.
                    closeReason = failureReason(t.javaClass.simpleName, response?.code)
                    retryAfterMs = retryAfterMillis(response?.header(RETRY_AFTER))
                    channel.close()
                }
            }

        fun attach(webSocket: WebSocket) {
            socket = webSocket
        }

        override fun send(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

        override fun close(
            code: Int,
            reason: String,
        ) {
            // OkHttp only accepts 1000 and 3000-4999 here; our protocol codes are all in 4000-4003.
            socket?.close(code, reason)
            channel.close()
        }
    }

    private companion object {
        const val TAG = "ScopeSync"
        const val INBOX_CAPACITY = 256
        const val RETRY_AFTER = "Retry-After"

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                // Client-side keepalive: the daemon pings every 30 s and OkHttp answers automatically, but
                // pinging outward is what detects a silently-dead link (a NAT dropping an idle connection)
                // instead of waiting for the next heal round to time out.
                .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
                .build()

        const val CONNECT_TIMEOUT_S = 15L
        const val PING_INTERVAL_S = 25L
    }
}

/**
 * What a dead socket is called in [SpoolSocket.closeReason]. A refused upgrade carries an HTTP status
 * and nothing else useful — `ProtocolException` names the layer that noticed, not what happened — so the
 * status becomes the reason. `101` is filtered out because it means the upgrade *succeeded* and the
 * socket died later; there the exception really is the whole story.
 */
internal fun failureReason(
    throwableName: String,
    httpCode: Int?,
): String = httpCode?.takeIf { it != HTTP_SWITCHING_PROTOCOLS }?.let { "http $it" } ?: throwableName

/**
 * `Retry-After` as milliseconds, or null when absent or unusable. Delta-seconds only: the HTTP-date form
 * is legal but would make our backoff a function of the spool's clock against ours, and the value is a
 * hint we are free to ignore. Clamped because this feeds a `delay` — a hostile or fat-fingered header
 * must not park a worker for a week.
 */
internal fun retryAfterMillis(header: String?): Long? =
    header
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(MAX_RETRY_AFTER_S)
        ?.let(TimeUnit.SECONDS::toMillis)

/** Ceiling on an honoured `Retry-After`, matching the reconnect loop's own longest wait. */
private const val MAX_RETRY_AFTER_S = 60L
private const val HTTP_SWITCHING_PROTOCOLS = 101
