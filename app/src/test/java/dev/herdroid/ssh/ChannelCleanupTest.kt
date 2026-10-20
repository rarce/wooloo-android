package dev.herdroid.ssh

import kotlinx.coroutines.*
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.Transport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class ChannelCleanupTest {
    /** Real sshj channel implementation with a peer that never acknowledges open/close. */
    private class Peer {
        val attached = ConcurrentHashMap.newKeySet<Channel>()
        val packetSent = CountDownLatch(1)
        var onPacket: (Message, SSHPacket) -> Unit = { _, _ -> }
        private val transport = proxy<Transport> { name, args -> when (name) {
            "getConfig" -> DefaultConfig()
            "getTimeoutMs" -> 30_000
            "write" -> {
                packetSent.countDown()
                val packet = SSHPacket(args!![0] as SSHPacket)
                onPacket(packet.readMessageID(), packet)
                0L
            }
            else -> error("Unexpected transport call: $name")
        } }
        val connection = proxy<Connection> { name, args -> when (name) {
            "getTransport" -> transport
            "getWindowSize" -> 2L * 1024 * 1024
            "getMaxPacketSize" -> 32 * 1024
            "getTimeoutMs" -> 30_000
            "nextID" -> 1
            "attach" -> { attached.add(args!![0] as Channel); null }
            "forget" -> { attached.remove(args!![0] as Channel); null }
            else -> error("Unexpected connection call: $name")
        } }
    }

    @Test(timeout = 5000)
    fun bridgeWaitingForStdinEofClosesWithoutAbortingSharedTransport(): Unit = runBlocking {
        val peer = Peer()
        val session = OpenSession(peer.connection)
        val inputEnded = AtomicBoolean()
        val acknowledged = AtomicBoolean()
        peer.onPacket = { message, _ ->
            if (message == Message.CHANNEL_EOF) inputEnded.set(true)
            if (message == Message.CHANNEL_CLOSE && inputEnded.get() && acknowledged.compareAndSet(false, true)) {
                // A bridge waiting for stdin only exits and acknowledges close after EOF.
                launch(Dispatchers.IO) { session.handle(Message.CHANNEL_CLOSE, SSHPacket()) }
            }
        }
        val aborted = AtomicInteger()
        ExecChannel(session, session) { aborted.incrementAndGet() }.closeBounded()
        assertTrue(inputEnded.get())
        assertEquals("A cooperative bridge must not disconnect other channels", 0, aborted.get())
        assertTrue(peer.attached.isEmpty())
    }

    @Test(timeout = 5000)
    fun observerIgnoringStdinEndsWithoutDisconnectingSharedTransport(): Unit = runBlocking {
        val peer = Peer()
        val session = OpenSession(peer.connection)
        val terminated = AtomicBoolean()
        val acknowledged = AtomicBoolean()
        peer.onPacket = { message, packet ->
            if (message == Message.CHANNEL_REQUEST) {
                packet.readUInt32AsInt()
                val request = packet.readString()
                packet.readBoolean()
                if (request == "signal" && packet.readString() == "TERM") terminated.set(true)
            }
            if (message == Message.CHANNEL_CLOSE && terminated.get() && acknowledged.compareAndSet(false, true)) {
                launch(Dispatchers.IO) { session.handle(Message.CHANNEL_CLOSE, SSHPacket()) }
            }
        }
        val aborted = AtomicInteger()
        ExecChannel(session, session) { aborted.incrementAndGet() }.closeBounded()
        assertTrue(terminated.get())
        assertEquals("Stopping this observer must keep the shared transport", 0, aborted.get())
        assertTrue(peer.attached.isEmpty())
    }
    private class OpenSession(connection: Connection) : SessionChannel(connection) {
        init { init(2, 2L * 1024 * 1024, 32L * 1024); openEvent.set() }
    }

    @Test(timeout = 5000)
    fun realSshjCloseWithoutPeerAcknowledgementIsBoundedAndForgetsChannel(): Unit = runBlocking {
        val peer = Peer()
        val session = OpenSession(peer.connection)
        val aborted = AtomicInteger()
        val channel = ExecChannel(session, session) { aborted.incrementAndGet() }
        val start = System.nanoTime()
        channel.closeBounded()
        assertTrue("Cleanup took too long", (System.nanoTime() - start) / 1_000_000 < 1500)
        assertEquals(1, aborted.get())
        assertTrue(peer.attached.isEmpty())
        assertFalse(channel.isOpen)
    }

    @Test(timeout = 5000)
    fun cancellationDuringRealSshjOpenForgetsTheOwnedChannel(): Unit = runBlocking {
        val peer = Peer()
        val aborted = AtomicInteger()
        val opening = async(Dispatchers.IO) {
            openExecChannel("never-opened", { SessionChannel(peer.connection) }) { aborted.incrementAndGet() }
        }
        assertTrue(peer.packetSent.await(2, TimeUnit.SECONDS))
        assertEquals(1, peer.attached.size)
        opening.cancelAndJoin()
        assertTrue(opening.isCancelled)
        assertTrue(aborted.get() >= 1)
        assertTrue(peer.attached.isEmpty())
    }

    @Test(timeout = 5000)
    fun cancellationClosesSocketReadThatIgnoresThreadInterruption(): Unit = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val peer = server.accept()
        val entered = CountDownLatch(1)
        val channel = object : CommandChannel {
            override val stdin = ByteArrayOutputStream()
            override val stdout = client.getInputStream()
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isOpen get() = !client.isClosed
            override fun abort() { client.close() }
            override fun close() { client.close() }
        }
        try {
            val reading = async(Dispatchers.IO) { channel.channelIo { entered.countDown(); channel.stdout.read() } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(1500) { reading.cancelAndJoin() }
            assertTrue(client.isClosed)
            peer.soTimeout = 1500
            assertEquals(-1, peer.getInputStream().read())
        } finally { client.close(); peer.close(); server.close() }
    }

    @Test(timeout = 5000)
    fun interruptibleChannelCancellationKeepsTheSharedTransport(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val aborted = AtomicInteger()
        val channel = object : CommandChannel {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isOpen = true
            override fun close() {}
            override fun abort() { aborted.incrementAndGet() }
        }
        val reading = async(Dispatchers.IO) {
            channel.channelIo {
                entered.countDown()
                CountDownLatch(1).await()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        reading.cancelAndJoin()
        assertTrue(reading.isCancelled)
        assertEquals(0, aborted.get())
    }

    private companion object {
        inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args!![0]
                    "toString" -> "Test ${T::class.java.simpleName}"
                    else -> call(method.name, args)
                }
            } as T
    }
}
