package dev.herdroid.ssh

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.Security
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CloudflareTunnelSocketTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Binary messages the server received from the socket under test. */
    private val received = LinkedBlockingQueue<ByteString>()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.put(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }))
    }

    private fun open(tunnel: CloudflareTunnel = CloudflareTunnel("example.trycloudflare.com")): Socket {
        val factory = CloudflareTunnelSocketFactory(tunnel, client, server.url("/").toString())
        return factory.createSocket().apply {
            connect(InetSocketAddress.createUnresolved(tunnel.hostname, 443), 5_000)
            soTimeout = 5_000
        }
    }

    private fun Socket.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = getInputStream().read(out, read, count - read)
            if (n == -1) fail("EOF after $read of $count bytes")
            read += n
        }
        return out
    }

    @Test
    fun bytesRoundTripBothWays() {
        upgrade { it.send("SSH-2.0-OpenSSH_10.3\r\n".encodeUtf8()) }
        val socket = open()
        assertTrue(socket.isConnected)
        assertEquals("SSH-2.0-OpenSSH_10.3\r\n", String(socket.readExactly(22)))

        socket.getOutputStream().apply {
            write("SSH-2.0-sshj\r\n".toByteArray())
            flush()
        }
        assertEquals("SSH-2.0-sshj\r\n", received.poll(5, TimeUnit.SECONDS)?.utf8())
        socket.close()
    }

    @Test(timeout = 8000)
    fun oversizedIncomingMessageDisconnectsInsteadOfBuffering() {
        upgrade { it.send(ByteArray(1024 * 1024 + 1).toByteString()) }
        val socket = open()
        try {
            try { socket.getInputStream().read(); fail("Expected receive limit failure") }
            catch (e: CloudflareTunnelException) { assertTrue(e.message!!.contains("receive buffer")) }
        } finally { socket.close() }
    }

    @Test(timeout = 8000)
    fun stalledConsumerCannotAccumulateUnboundedIncomingBytes() {
        upgrade { ws -> repeat(6) { ws.send(ByteArray(1024 * 1024).toByteString()) } }
        val socket = open()
        try {
            // Leave SSH unread until the receive budget is exhausted.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!socket.isClosed && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Receive budget should close the tunnel", socket.isClosed)
            try {
                while (socket.getInputStream().read(ByteArray(8192)) >= 0) { }
                fail("Expected receive limit failure")
            } catch (e: CloudflareTunnelException) {
                assertTrue(e.message!!.contains("receive buffer"))
            }
        } finally { socket.close() }
    }

    @Test
    fun largeWritesAreSplitIntoMessages() {
        upgrade()
        val socket = open()
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        socket.getOutputStream().apply {
            write(payload)
            flush()
        }
        val collected = Buffer()
        var messages = 0
        while (collected.size < payload.size) {
            val message = received.poll(5, TimeUnit.SECONDS) ?: fail("only ${collected.size} bytes arrived")
            collected.write(message as ByteString)
            messages++
        }
        assertArrayEquals(payload, collected.readByteArray())
        assertTrue("expected several messages, got $messages", messages > 1)
        socket.close()
    }

    @Test
    fun serverCloseIsEndOfStream() {
        upgrade {
            it.send("bye".encodeUtf8())
            it.close(1000, null)
        }
        val socket = open()
        assertEquals("bye", String(socket.readExactly(3)))
        assertEquals(-1, socket.getInputStream().read())
        assertEquals(-1, socket.getInputStream().read())
        socket.close()
    }

    @Test
    fun readHonorsSoTimeout() {
        upgrade()
        val socket = open()
        socket.soTimeout = 100
        try {
            socket.getInputStream().read()
            fail("read should time out")
        } catch (expected: SocketTimeoutException) {
        }
        socket.close()
    }

    @Test
    fun closeUnblocksAPendingRead() {
        upgrade()
        val socket = open()
        socket.soTimeout = 0
        val result = LinkedBlockingQueue<Int>()
        Thread { result.put(runCatching { socket.getInputStream().read() }.getOrDefault(-2)) }.start()
        Thread.sleep(100)
        socket.close()
        assertEquals(-1, result.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun sendsAccessServiceTokenHeaders() {
        upgrade()
        open(CloudflareTunnel("ssh.example.com", "client-id.access", "s3cret")).close()
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("client-id.access", request.getHeader("CF-Access-Client-Id"))
        assertEquals("s3cret", request.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun accessRejectionIsReported() {
        server.enqueue(MockResponse().setResponseCode(403))
        val error = connectFailure(CloudflareTunnel("ssh.example.com"))
        assertEquals(403, error.status)
        assertTrue(error.isAccessDenied)
        assertTrue(error.message!!, error.message!!.contains("Access service token"))
    }

    @Test
    fun stoppedQuickTunnelAsksForTheNewQrCode() {
        server.enqueue(MockResponse().setResponseCode(530))
        val error = connectFailure(CloudflareTunnel("old-name.trycloudflare.com"))
        assertEquals(530, error.status)
        assertTrue(error.message!!, error.message!!.contains("scan the new QR code"))
    }

    @Test
    fun stoppedNamedTunnelPointsAtRemoteAccess() {
        server.enqueue(MockResponse().setResponseCode(404))
        val error = connectFailure(CloudflareTunnel("ssh.example.com"))
        assertTrue(error.message!!, error.message!!.contains("Remote Access"))
    }

    private fun connectFailure(tunnel: CloudflareTunnel): CloudflareTunnelException {
        try {
            open(tunnel)
        } catch (e: CloudflareTunnelException) {
            return e
        }
        fail("connect should fail")
        throw AssertionError()
    }

    /** Live check against a real tunnel: `cloudflared tunnel --url ssh://localhost:22`. */
    @Test
    fun liveTunnelSpeaksSsh() {
        val host = System.getenv("HERDROID_CF_TUNNEL_HOST")
        assumeTrue("set HERDROID_CF_TUNNEL_HOST to run", !host.isNullOrBlank())
        val tunnel = CloudflareTunnel(host!!)
        val socket = CloudflareTunnelSocketFactory(tunnel).createSocket().apply {
            connect(InetSocketAddress.createUnresolved(host, 443), 15_000)
            soTimeout = 15_000
        }
        socket.use { assertEquals("SSH-2.0-", String(it.readExactly(8))) }
    }

    /** sshj over a real tunnel: key exchange and host key check succeed, a bogus login is refused. */
    @Test
    fun liveTunnelCarriesSshj(): Unit = runBlocking {
        val host = System.getenv("HERDROID_CF_TUNNEL_HOST")
        assumeTrue("set HERDROID_CF_TUNNEL_HOST to run", !host.isNullOrBlank())
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        val challenges = mutableListOf<HostKeyChallenge>()
        val ssh = SshSession(host!!, 22, "herdroid-nobody", { _, _ -> null }, CloudflareTunnel(host)) {
            challenges += it
            true
        }
        try {
            ssh.connect(SshCredential.Password("not-a-password"))
            fail("login should be refused")
        } catch (expected: SshAuthException) {
        } finally {
            ssh.close()
        }
        val challenge = challenges.single()
        assertEquals(CloudflareTunnelSocketFactory.TUNNEL_PORT, challenge.port)
        assertEquals(host, challenge.displayTarget)
        assertTrue(challenge.fingerprint.startsWith("SHA256:"))
        println("tunnel host key ${challenge.keyType} ${challenge.fingerprint}")
    }
}
