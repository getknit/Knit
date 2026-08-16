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
        if (!accepts(url)) {
            Log.w(TAG, "refusing spool url (scheme not allowed): ${redact(url)}")
            return null
        }
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
        if (request == null) {
            Log.w(TAG, "unparseable spool url: ${redact(url)}")
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

    /** Whether this URL's scheme is usable in this build. `wss://` always; `ws://` only in debug. */
    private fun accepts(url: String): Boolean = url.startsWith(WSS_SCHEME) || (allowCleartext && url.startsWith(WS_SCHEME))

    private class OkHttpSpoolSocket(
        private val channel: Channel<ByteArray>,
    ) : SpoolSocket {
        @Volatile
        private var socket: WebSocket? = null

        override val incoming: ReceiveChannel<ByteArray> get() = channel

        val listener =
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: WebSocket,
                    bytes: ByteString,
                ) {
                    // Records are binary by definition (§7.1); a text frame is not one of ours.
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
        const val WSS_SCHEME = "wss://"
        const val WS_SCHEME = "ws://"
        const val INBOX_CAPACITY = 256

        /** Strips any `?k=` bearer token before a URL reaches the log. */
        fun redact(url: String): String = url.substringBefore('?')

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
