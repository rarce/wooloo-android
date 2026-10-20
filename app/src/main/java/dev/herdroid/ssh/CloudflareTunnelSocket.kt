package dev.herdroid.ssh

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * A machine reachable through a Cloudflare Tunnel whose service is `ssh://localhost:22`.
 * The access credentials are a Cloudflare Access service token, needed only when the
 * hostname is protected by Access.
 */
data class CloudflareTunnel(
    val hostname: String,
    val accessClientId: String? = null,
    val accessClientSecret: String? = null,
) {
    val isQuickTunnel: Boolean get() = hostname.endsWith(".trycloudflare.com", ignoreCase = true)

    override fun toString() = "CloudflareTunnel($hostname)"
}

/** The tunnel couldn't be opened or broke. [status] is the HTTP status of a refused upgrade. */
class CloudflareTunnelException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /** Cloudflare Access refused the request: the service token is missing or wrong. */
    val isAccessDenied: Boolean get() = status == 401 || status == 403
}

/** Hands sshj sockets that carry SSH over the tunnel's WebSocket, like `cloudflared access ssh`. */
class CloudflareTunnelSocketFactory(
    private val tunnel: CloudflareTunnel,
    private val client: OkHttpClient = sharedClient,
    private val url: String = "wss://${tunnel.hostname}/",
) : SocketFactory() {
    override fun createSocket(): Socket = CloudflareTunnelSocket(tunnel, client, url)

    override fun createSocket(host: String?, port: Int): Socket = connected()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = connected()
    override fun createSocket(host: InetAddress?, port: Int): Socket = connected()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connected()

    private fun connected(): Socket = createSocket().also {
        it.connect(InetSocketAddress.createUnresolved(tunnel.hostname, TUNNEL_PORT), 0)
    }

    companion object {
        /** sshj's port for a tunnel; the WebSocket always goes to 443. Also keys known hosts. */
        const val TUNNEL_PORT = 443

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // Cloudflare drops idle WebSockets; pings keep a quiet session open.
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

/**
 * A [Socket] whose byte stream is the binary messages of a WebSocket: what Cloudflare's
 * `ssh://` tunnel service expects. Only the parts of [Socket] that sshj uses are implemented.
 */
class CloudflareTunnelSocket internal constructor(
    private val tunnel: CloudflareTunnel,
    private val client: OkHttpClient,
    private val url: String,
) : Socket() {
    private val incoming = LinkedBlockingQueue<ByteString>(129)
    private var incomingBytes = 0
    @Volatile private var peerEnded = false
    private val opened = CountDownLatch(1)

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var openFailure: IOException? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    @Volatile private var timeoutMs = 0
    @Volatile private var remote: InetSocketAddress? = null

    private val input = TunnelInputStream()
    private val output = TunnelOutputStream()

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("Socket is closed")
        if (connected) throw SocketException("Already connected")
        remote = endpoint as? InetSocketAddress
        val request = try {
            Request.Builder().url(url).apply {
                tunnel.accessClientId?.takeIf { it.isNotBlank() }?.let { header("CF-Access-Client-Id", it) }
                tunnel.accessClientSecret?.takeIf { it.isNotBlank() }?.let { header("CF-Access-Client-Secret", it) }
            }.build()
        } catch (_: IllegalArgumentException) {
            // OkHttp header validation includes the invalid value in its exception.
            throw CloudflareTunnelException("Invalid Cloudflare address or Access credentials. Check the machine settings.")
        }
        val socket = client.newWebSocket(request, Listener())
        webSocket = socket
        val waitMs = if (timeout > 0) timeout.toLong() else DEFAULT_CONNECT_TIMEOUT_MS
        val interrupted = try {
            !opened.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            socket.cancel()
            Thread.currentThread().interrupt()
            throw SocketException("Interrupted opening the Cloudflare tunnel")
        }
        if (interrupted) {
            socket.cancel()
            throw SocketTimeoutException("Timed out opening the Cloudflare tunnel to ${tunnel.hostname}")
        }
        openFailure?.let { throw it }
        if (closed) throw SocketException("Socket is closed")
        connected = true
    }

    override fun isConnected(): Boolean = connected
    override fun isClosed(): Boolean = closed
    override fun getInetAddress(): InetAddress? = remote?.address
    override fun getRemoteSocketAddress(): SocketAddress? = remote
    override fun getPort(): Int = remote?.port ?: 0

    override fun setSoTimeout(timeout: Int) {
        timeoutMs = timeout
    }

    override fun getSoTimeout(): Int = timeoutMs

    // sshj tunes these on real sockets; they mean nothing for a WebSocket.
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay(): Boolean = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getKeepAlive(): Boolean = true

    override fun getInputStream(): InputStream {
        if (!connected) throw SocketException("Socket is not connected")
        return input
    }

    override fun getOutputStream(): OutputStream {
        if (!connected) throw SocketException("Socket is not connected")
        return output
    }

    override fun shutdownInput() = Unit
    override fun shutdownOutput() = Unit

    override fun close() {
        if (closed) return
        closed = true
        webSocket?.let { if (!it.close(NORMAL_CLOSURE, null)) it.cancel() }
        incoming.offer(END)
        opened.countDown()
    }

    override fun toString() = "CloudflareTunnelSocket[${tunnel.hostname}]"

    @Synchronized
    private fun enqueue(bytes: ByteString) {
        if (closed || failure != null || peerEnded) return
        if (bytes.size > MAX_INCOMING_MESSAGE_BYTES || incomingBytes.toLong() + bytes.size > MAX_INCOMING_BYTES ||
            incoming.size >= 128 || !incoming.offer(bytes)
        ) {
            fail(CloudflareTunnelException("Cloudflare tunnel receive buffer exceeded its limit; reconnect"))
            webSocket?.cancel()
            return
        }
        incomingBytes += bytes.size
    }

    @Synchronized
    private fun fail(e: IOException) {
        if (failure == null) failure = e
        closed = true
        incoming.clear()
        incomingBytes = 0
        incoming.offer(END)
    }

    @Synchronized
    private fun dequeued(bytes: ByteString) {
        incomingBytes = maxOf(0, incomingBytes - bytes.size)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size > 0) enqueue(bytes)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // cloudflared only sends binary; take text as UTF-8 bytes rather than dropping it.
            if (text.isNotEmpty()) enqueue(text.toByteArray().toByteString())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            peerEnded = true
            webSocket.close(NORMAL_CLOSURE, null)
            incoming.offer(END)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            peerEnded = true
            incoming.offer(END)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (opened.count > 0L) {
                openFailure = openError(t, response)
                opened.countDown()
            } else if (!closed) {
                fail(CloudflareTunnelException("Cloudflare tunnel to ${tunnel.hostname} broke: ${t.message ?: t}", cause = t))
            }
            incoming.offer(END)
        }
    }

    private fun openError(t: Throwable, response: Response?): IOException {
        val status = response?.code
        response?.close()
        val host = tunnel.hostname
        return when {
            status == null -> t as? IOException
                ?: CloudflareTunnelException("Couldn't open the Cloudflare tunnel to $host: ${t.message ?: t}", cause = t)
            status == 401 || status == 403 -> CloudflareTunnelException(
                if (tunnel.accessClientId.isNullOrBlank()) {
                    "Cloudflare Access blocked $host (HTTP $status). Add the Access service token to this machine."
                } else {
                    "Cloudflare Access rejected the service token for $host (HTTP $status). Check the client ID and secret."
                },
                status,
                t,
            )
            status == 404 || status == 502 || status == 503 || status == 530 -> CloudflareTunnelException(
                if (tunnel.isQuickTunnel) {
                    "Cloudflare tunnel $host isn't running (HTTP $status). Quick tunnel hostnames change " +
                        "every time Remote Access restarts: scan the new QR code in wooloo."
                } else {
                    "Cloudflare tunnel $host isn't running (HTTP $status). Is Remote Access on in wooloo?"
                },
                status,
                t,
            )
            else -> CloudflareTunnelException(
                "Cloudflare tunnel $host refused the connection (HTTP $status). " +
                    "Is the tunnel's service ssh://localhost:22?",
                status,
                t,
            )
        }
    }

    private inner class TunnelInputStream : InputStream() {
        private var current: ByteString? = null
        private var offset = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        @Synchronized
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val chunk = nextChunk() ?: return -1
            val n = minOf(len, chunk.size - offset)
            chunk.copyInto(offset, b, off, n)
            offset += n
            if (offset == chunk.size) current = null
            return n
        }

        override fun available(): Int = current?.let { it.size - offset } ?: 0

        private fun nextChunk(): ByteString? {
            current?.let { return it }
            if (ended) return endOfStream()
            val timeout = timeoutMs
            val next = try {
                if (timeout > 0) incoming.poll(timeout.toLong(), TimeUnit.MILLISECONDS) else incoming.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("Interrupted reading the Cloudflare tunnel")
            } ?: throw SocketTimeoutException("Read timed out")
            if (next === END) {
                ended = true
                return endOfStream()
            }
            dequeued(next)
            current = next
            offset = 0
            return next
        }

        private fun endOfStream(): ByteString? {
            failure?.let { throw it }
            return null
        }

        override fun close() = this@CloudflareTunnelSocket.close()
    }

    private inner class TunnelOutputStream : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            ensureOpen()
            var at = off
            val end = off + len
            while (at < end) {
                val n = minOf(end - at, MAX_MESSAGE_BYTES - buffer.size())
                buffer.write(b, at, n)
                at += n
                if (buffer.size() >= MAX_MESSAGE_BYTES) flush()
            }
        }

        @Synchronized
        override fun flush() {
            if (buffer.size() == 0) return
            ensureOpen()
            val socket = webSocket ?: throw SocketException("Socket is not connected")
            // OkHttp queues sends without limit until 16 MiB, then fails the socket: wait instead.
            while (socket.queueSize() > MAX_QUEUED_BYTES) {
                ensureOpen()
                try {
                    Thread.sleep(BACKPRESSURE_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Interrupted writing to the Cloudflare tunnel")
                }
            }
            val message = buffer.toByteArray().toByteString()
            buffer.reset()
            if (!socket.send(message)) {
                throw failure ?: CloudflareTunnelException("Cloudflare tunnel to ${tunnel.hostname} is closed")
            }
        }

        private fun ensureOpen() {
            failure?.let { throw it }
            if (closed || peerEnded) throw SocketException("Socket is closed")
        }

        override fun close() = this@CloudflareTunnelSocket.close()
    }

    private companion object {
        val END: ByteString = ByteArray(0).toByteString()
        const val MAX_INCOMING_MESSAGE_BYTES = 1024 * 1024
        const val MAX_INCOMING_BYTES = 4 * 1024 * 1024
        const val NORMAL_CLOSURE = 1000
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_MESSAGE_BYTES = 32 * 1024
        const val MAX_QUEUED_BYTES = 1024 * 1024L
        const val BACKPRESSURE_POLL_MS = 5L
    }
}
