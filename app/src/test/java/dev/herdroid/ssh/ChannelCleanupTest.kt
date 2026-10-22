package dev.herdroid.ssh

import kotlinx.coroutines.*
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.ConnectionException
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
        val requests = AtomicInteger()
        peer.onPacket = { message, _ ->
            if (message == Message.CHANNEL_REQUEST) requests.incrementAndGet()
            if (message == Message.CHANNEL_EOF) inputEnded.set(true)
            if (message == Message.CHANNEL_CLOSE && inputEnded.get() && acknowledged.compareAndSet(false, true)) {
                // A bridge waiting for stdin only exits and acknowledges close after EOF.
                launch(Dispatchers.IO) { session.handle(Message.CHANNEL_CLOSE, SSHPacket()) }
            }
        }
        val aborted = AtomicInteger()
        ExecChannel(session, session) { aborted.incrementAndGet() }.closeBounded()
        assertTrue(inputEnded.get())
        assertEquals("Completed requests must not send a late TERM to a freed server channel", 0, requests.get())
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
        val channel = ExecChannel(session, session) { aborted.incrementAndGet() }
        channel.closeBounded(terminateRemote = true)
        channel.closeBounded(terminateRemote = true)
        assertTrue(terminated.get())
        assertEquals("Stopping this observer must keep the shared transport", 0, aborted.get())
        assertTrue(peer.attached.isEmpty())
    }
    private class OpenSession(connection: Connection) : SessionChannel(connection) {
        init { init(2, 2L * 1024 * 1024, 32L * 1024); openEvent.set() }
    }

    @Test(timeout = 5000)
    fun realSshjCloseWithoutAcknowledgementAbortsOnlyOnceTheTransportStopsAnswering(): Unit = runBlocking {
        val peer = Peer()
        val session = OpenSession(peer.connection)
        val aborted = AtomicInteger()
        val released = AtomicInteger()
        val channel = ExecChannel(session, session, transportHealthy = { false }, onReleased = { released.incrementAndGet() }) {
            aborted.incrementAndGet()
        }
        val start = System.nanoTime()
        channel.closeBounded(stuckMs = 300)
        assertTrue("Cleanup must not block the caller", (System.nanoTime() - start) / 1_000_000 < 1000)
        assertEquals("No abort before the stuck deadline", 0, aborted.get())
        // abort() closes the transport first, then forgets the channel and releases its slot.
        awaitTrue { aborted.get() == 1 && peer.attached.isEmpty() && released.get() == 1 }
        assertFalse(channel.isOpen)
    }

    @Test(timeout = 5000)
    fun slowCloseAcknowledgementKeepsTheTransportAndHoldsItsSlotUntilAcknowledged(): Unit = runBlocking {
        val peer = Peer()
        val session = OpenSession(peer.connection)
        val closeSent = CountDownLatch(1)
        peer.onPacket = { message, _ -> if (message == Message.CHANNEL_CLOSE) closeSent.countDown() }
        val aborted = AtomicInteger()
        val released = AtomicInteger()
        val channel = ExecChannel(session, session, transportHealthy = { true }, onReleased = { released.incrementAndGet() }) {
            aborted.incrementAndGet()
        }
        channel.closeBounded(stuckMs = 200)
        assertTrue(closeSent.await(2, TimeUnit.SECONDS))
        delay(800) // several stuck deadlines pass on a transport that still answers
        assertEquals("A slow peer must not cost the shared transport", 0, aborted.get())
        assertEquals("The channel still counts against MaxSessions until acknowledged", 0, released.get())
        session.handle(Message.CHANNEL_CLOSE, SSHPacket())
        awaitTrue { released.get() == 1 }
        assertTrue(peer.attached.isEmpty())
        assertEquals(0, aborted.get())
        assertTrue(SshSession.MAX_EXEC_CHANNELS < 10)
    }

    @Test(timeout = 5000)
    fun closeStuckInAWriteAbortsAfterTheDeadlineOnlyWhenUnhealthy(): Unit = runBlocking {
        val healthy = AtomicBoolean(true)
        val aborted = AtomicInteger()
        val unblock = CountDownLatch(1)
        val channel = object : CommandChannel {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isOpen = true
            // A socket write that ignores interruption: only closing the transport releases it.
            override fun close() { while (true) try { unblock.await(); return } catch (_: InterruptedException) { } }
            override fun abort() { aborted.incrementAndGet(); unblock.countDown() }
            override suspend fun isTransportHealthy() = healthy.get()
        }
        channel.closeBounded(stuckMs = 200)
        delay(700)
        assertEquals(0, aborted.get())
        healthy.set(false)
        awaitTrue { aborted.get() == 1 }
    }

    @Test(timeout = 5000)
    fun cancellationDuringRealSshjOpenKeepsTransportAndClosesTheLateChannel(): Unit = runBlocking {
        val peer = Peer()
        val aborted = AtomicInteger()
        val released = AtomicInteger()
        lateinit var session: SessionChannel
        peer.onPacket = { message, packet ->
            when (message) {
                Message.CHANNEL_REQUEST -> {
                    packet.readUInt32AsInt()
                    if (packet.readString() == "exec") launch(Dispatchers.IO) { session.handle(Message.CHANNEL_SUCCESS, SSHPacket()) }
                }
                Message.CHANNEL_CLOSE -> launch(Dispatchers.IO) { session.handle(Message.CHANNEL_CLOSE, SSHPacket()) }
                else -> Unit
            }
        }
        val opening = async(Dispatchers.IO) {
            openExecChannel("late", { SessionChannel(peer.connection).also { session = it } },
                onReleased = { released.incrementAndGet() }) { aborted.incrementAndGet() }
        }
        assertTrue(peer.packetSent.await(2, TimeUnit.SECONDS))
        opening.cancelAndJoin()
        assertTrue(opening.isCancelled)
        assertEquals("Giving up on an open must not drop the shared transport", 0, aborted.get())
        assertEquals("The pending channel stays attached for its late confirmation", 1, peer.attached.size)
        // The server confirms after the pane switch: the channel is then closed gracefully.
        session.handle(Message.CHANNEL_OPEN_CONFIRMATION,
            SSHPacket().putUInt32(7).putUInt32(2L * 1024 * 1024).putUInt32(32L * 1024))
        awaitTrue { peer.attached.isEmpty() && released.get() == 1 }
        assertEquals(0, aborted.get())
    }

    @Test(timeout = 5000)
    fun execFailureAfterOpenClosesTheChannelAndReleasesItsSlotOnce(): Unit = runBlocking {
        val peer = Peer()
        val aborted = AtomicInteger()
        val released = AtomicInteger()
        lateinit var session: SessionChannel
        peer.onPacket = { message, _ ->
            if (message == Message.CHANNEL_CLOSE) launch(Dispatchers.IO) { session.handle(Message.CHANNEL_CLOSE, SSHPacket()) }
        }
        val failure = runCatching {
            openExecChannel("refused-exec", {
                object : SessionChannel(peer.connection) {
                    init { init(3, 2L * 1024 * 1024, 32L * 1024); openEvent.set() }
                    override fun exec(command: String) = throw ConnectionException("exec request failed")
                }.also { session = it }
            }, onReleased = { released.incrementAndGet() }) { aborted.incrementAndGet() }
        }.exceptionOrNull()
        assertTrue(failure is ConnectionException)
        awaitTrue { released.get() == 1 && peer.attached.isEmpty() }
        delay(200)
        assertEquals("Released exactly once", 1, released.get())
        assertEquals(0, aborted.get())
    }

    @Test(timeout = 5000)
    fun refusedOpenIsAChannelErrorThatKeepsTheTransport(): Unit = runBlocking {
        val peer = Peer()
        val aborted = AtomicInteger()
        val released = AtomicInteger()
        val refused = runCatching {
            openExecChannel("refused", {
                object : SessionChannel(peer.connection) {
                    override fun open() = throw OpenFailException("session", OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "open failed")
                }
            }, onReleased = { released.incrementAndGet() }) { aborted.incrementAndGet() }
        }.exceptionOrNull()
        assertTrue(refused is OpenFailException)
        assertEquals(0, aborted.get())
        assertEquals(1, released.get())
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
            val reading = async(Dispatchers.IO) { channel.channelIo(stuckMs = 250) { entered.countDown(); channel.stdout.read() } }
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

    private suspend fun awaitTrue(condition: () -> Boolean) = withTimeout(3000) {
        while (!condition()) delay(10)
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
