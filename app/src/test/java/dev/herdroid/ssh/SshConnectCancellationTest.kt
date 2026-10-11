package dev.herdroid.ssh

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SshConnectCancellationTest {
    @Test(timeout = 5000)
    fun cancellingAStalledHandshakeClosesThePeerSocket(): Unit = runBlocking {
        val accepted = AtomicReference<Socket?>()
        val peerClosed = CompletableDeferred<Boolean>()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 3000
                    // Read the client's banner but never send our own.
                    while (socket.getInputStream().read() != -1) { }
                    peerClosed.complete(true)
                }
            } catch (_: SocketException) { peerClosed.complete(true) }
            catch (e: Exception) { peerClosed.completeExceptionally(e) }
        }
        val ssh = SshSession("127.0.0.1", server.localPort, "test", { _, _ -> null }) { false }
        try {
            try {
                withTimeout(200) { ssh.connect(SshCredential.Password("test-only")) }
                fail("Expected timeout")
            } catch (_: TimeoutCancellationException) { }
            assertFalse(ssh.isConnected)
            assertTrue(withTimeout(3500) { peerClosed.await() })
        } finally {
            ssh.close()
            accepted.get()?.close()
            server.close()
            peer.join(1000)
        }
    }
}
