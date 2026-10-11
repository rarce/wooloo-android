package dev.herdroid.herdr

import dev.herdroid.ssh.CommandChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.Base64
import java.util.concurrent.CountDownLatch

class TerminalStreamLimitsTest {
    private class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        @Volatile var consumed = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) consumed += it
        }
    }
    private class Stalled : InputStream() {
        override fun read(): Int {
            try { CountDownLatch(1).await() } catch (e: InterruptedException) { throw InterruptedIOException() }
            return -1
        }
    }
    private class Channel(
        override val stdout: InputStream,
        override val stdin: OutputStream = ByteArrayOutputStream(),
    ) : CommandChannel {
        @Volatile var closed = false
        override val stderr = byteArrayOf().inputStream()
        override val isOpen get() = !closed
        override fun close() { closed = true }
    }

    @Test(timeout = 5000)
    fun slowConsumerAppliesBackpressureWithoutDroppingIncrementalFrames(): Unit = runBlocking {
        val payload = Base64.getEncoder().encodeToString(ByteArray(4096) { 32 })
        val bytes = (1..40).joinToString("") {
            "{\"type\":\"terminal.frame\",\"seq\":$it,\"width\":80,\"height\":24,\"bytes\":\"$payload\"}\n"
        }.toByteArray()
        val input = CountingInput(bytes)
        val channel = Channel(input)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stream = TerminalStream.fromChannel(channel, scope)
            withTimeout(1000) { while (input.consumed == 0) delay(1) }
            delay(50)
            assertTrue("Producer should stop when consumer isn't collecting", input.consumed < bytes.size)
            val events = withTimeout(2000) { stream.events.toList() }
            assertEquals((1L..40L).toList(), events.filterIsInstance<TerminalEvent.Frame>().map { it.seq })
            assertTrue(events.last() is TerminalEvent.Closed)
            assertTrue(channel.closed)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000)
    fun invalidDimensionsCloseBeforeGridAllocation(): Unit = runBlocking {
        val channel = Channel("{\"type\":\"terminal.frame\",\"width\":2147483647,\"height\":2}\n".byteInputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val events = TerminalStream.fromChannel(channel, scope).events.toList()
            assertTrue(events.none { it is TerminalEvent.Frame })
            assertTrue((events.last() as TerminalEvent.Closed).reason!!.contains("dimensions"))
            assertTrue(channel.closed)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000)
    fun oversizedUnterminatedRecordIsRejected(): Unit = runBlocking {
        val channel = Channel("x".repeat(1_500_001).byteInputStream())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val events = TerminalStream.fromChannel(channel, scope).events.toList()
            assertTrue((events.last() as TerminalEvent.Closed).reason!!.contains("exceeds"))
            assertTrue(channel.closed)
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000)
    fun saturatedInputIsReportedInsteadOfSilentlyDropped(): Unit = runBlocking {
        val channel = Channel(Stalled(), object : OutputStream() {
            override fun write(b: Int) { Stalled().read() }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stream = TerminalStream.fromChannel(channel, scope)
            repeat(100) { stream.input("x") }
            val events = withTimeout(2000) { stream.events.toList() }
            assertTrue((events.last() as TerminalEvent.Closed).reason!!.contains("buffer is full"))
            withTimeout(1000) { while (!channel.closed) delay(1) }
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000)
    fun disposalClosesChannelEvenWhenScopeIsCancelledImmediately(): Unit = runBlocking {
        val channel = Channel(Stalled())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stream = TerminalStream.fromChannel(channel, scope)
        stream.close()
        scope.cancel()
        withTimeout(1000) { while (!channel.closed) delay(1) }
    }
}
