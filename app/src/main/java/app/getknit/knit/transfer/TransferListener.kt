package app.getknit.knit.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * The host's end of a transfer's TCP rendezvous: one listener per address the group interface carries, and
 * the single client whose proof names the transfer.
 *
 * Both address families are bound because the receiver picks between them on its own — a phone on Android 13+
 * may join with IPv6 link-local provisioning and never hold an IPv4 address on the group, and the host is
 * never told which it did. Binding the group's own addresses rather than the wildcard is what keeps the port
 * off the phone's ordinary Wi-Fi, and only a peer inside one of the group's subnets is ever read from.
 */
internal class TransferListener(
    private val group: HostedGroup,
    private val port: Int,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val windowMs: Long,
    private val soTimeoutMs: Int,
    private val log: (String) -> Unit,
) {
    private val servers = Collections.synchronizedList(mutableListOf<ServerSocket>())

    /** Binds every address the group carries; throws when not one of them can be listened on. */
    fun bind(bufferBytes: Int) {
        var last: IOException? = null
        for (address in group.addresses) {
            try {
                servers +=
                    ServerSocket().apply {
                        receiveBufferSize = bufferBytes
                        bind(InetSocketAddress(address.address, port), 1)
                    }
            } catch (e: IOException) {
                log("cannot listen on ${address.address.hostAddress}: $e")
                last = e
            }
        }
        if (servers.isEmpty()) throw last ?: IOException("no address to listen on")
    }

    /**
     * The client whose proof carries [id], from whichever listener it reaches first. Anything else that dials
     * a port is dropped and the listeners keep waiting out the window; null means nobody came in time.
     */
    suspend fun accept(
        key: ByteArray,
        id: String,
    ): Socket? =
        coroutineScope {
            val winner = CompletableDeferred<Socket?>()
            val bound = servers.toList()
            val races =
                bound.map { server ->
                    launch(io) {
                        val socket = acceptOn(server, key, id)
                        if (socket != null && !winner.complete(socket)) runCatching { socket.close() }
                    }
                }
            launch(io) {
                races.joinAll()
                winner.complete(null)
            }
            val socket = winner.await()
            // Closing the listeners is also what unblocks the accept() calls that lost the race.
            close()
            races.forEach { it.cancel() }
            socket
        }

    fun close() {
        servers.toList().forEach { runCatching { it.close() } }
        servers.clear()
    }

    private fun acceptOn(
        server: ServerSocket,
        key: ByteArray,
        id: String,
    ): Socket? {
        val deadline = clock() + windowMs
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return null
            server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    // The window ran out, or another listener won the race and closed this one.
                    return null
                }
            if (admit(socket, key, id)) return socket
            runCatching { socket.close() }
        }
    }

    /** A caller must both sit inside one of the group's subnets and prove it holds the transfer's key. */
    private fun admit(
        socket: Socket,
        key: ByteArray,
        id: String,
    ): Boolean {
        if (group.addresses.none { socket.inetAddress.inPrefix(it.address, it.prefixLength) }) return false
        socket.soTimeout = soTimeoutMs
        return try {
            TransferStream.readProof(socket.getInputStream(), key, id)
        } catch (_: IOException) {
            false
        }
    }
}
