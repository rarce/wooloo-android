package dev.herdroid.herdr

import dev.herdroid.ssh.CommandChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HerdrApiDeadlineTest {
    private class Stalled : InputStream() {
        val entered = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            try { CountDownLatch(1).await() } catch (e: InterruptedException) {
                throw InterruptedIOException().apply { initCause(e) }
            }
            return -1
        }
    }
    private class FakeChannel(
        override val stdout: InputStream,
        override val stderr: InputStream = ByteArrayInputStream(byteArrayOf()),
        override val stdin: OutputStream = ByteArrayOutputStream(),
    ) : CommandChannel {
        @Volatile var closed = false
        override val isOpen get() = !closed
        override fun close() { closed = true }
    }

    @Test(timeout = 5000)
    fun stalledReadsReleaseAllPermitsAndChannels(): Unit = runBlocking {
        val channels = CopyOnWriteArrayList<FakeChannel>()
        var healthy = false
        val api = HerdrApi({
            FakeChannel(if (healthy) "{\"result\":{\"ok\":true}}\n".byteInputStream() else Stalled())
                .also { channels += it }
        }, "default", null)
        coroutineScope {
            repeat(4) { launch {
                try { api.request("ping", timeoutMs = 100); fail("Expected timeout") }
                catch (_: SocketTimeoutException) { }
            } }
        }
        assertEquals(4, channels.size)
        assertTrue(channels.all { it.closed })
        healthy = true
        assertEquals("true", api.request("ping", timeoutMs = 1000)["ok"].toString())
    }

    @Test(timeout = 5000)
    fun callerCancellationRemainsCancellation(): Unit = runBlocking {
        val input = Stalled()
        val channel = FakeChannel(input)
        val api = HerdrApi({ channel }, "default", null)
        val job = async(Dispatchers.IO) { api.request("ping", timeoutMs = 10_000) }
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(channel.closed)
    }

    @Test(timeout = 5000)
    fun stalledErrorStreamAlsoHasDeadline(): Unit = runBlocking {
        val channel = FakeChannel(byteArrayOf().inputStream(), Stalled())
        val api = HerdrApi({ channel }, "default", null)
        try { api.request("ping", timeoutMs = 100); fail("Expected timeout") }
        catch (_: SocketTimeoutException) { }
        assertTrue(channel.closed)
    }

    @Test(timeout = 5000)
    fun stalledWriteAlsoHasDeadline(): Unit = runBlocking {
        val blocked = object : OutputStream() {
            override fun write(value: Int) { Stalled().read() }
        }
        val channel = FakeChannel(byteArrayOf().inputStream(), stdin = blocked)
        val api = HerdrApi({ channel }, "default", null)
        try { api.request("ping", timeoutMs = 100); fail("Expected timeout") }
        catch (_: SocketTimeoutException) { }
        assertTrue(channel.closed)
    }

    @Test(timeout = 5000)
    fun cancellingSubscriptionClosesBlockedHandshake(): Unit = runBlocking {
        val input = Stalled()
        val channel = FakeChannel(input)
        val api = HerdrApi({ channel }, "default", null)
        val job = launch(Dispatchers.IO) { api.subscribe(listOf("workspace.created")).collect() }
        assertTrue(input.entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(channel.closed)
    }

    @Test(timeout = 5000)
    fun queuedRequestDeadlineDoesNotOpenAnotherChannel(): Unit = runBlocking {
        val opened = CountDownLatch(4)
        val channels = CopyOnWriteArrayList<FakeChannel>()
        val api = HerdrApi({
            FakeChannel(Stalled()).also { channels += it; opened.countDown() }
        }, "default", null)
        val jobs = List(4) { launch(Dispatchers.IO) { api.request("ping", timeoutMs = 10_000) } }
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            try { api.request("ping", timeoutMs = 50); fail("Expected queue timeout") }
            catch (_: SocketTimeoutException) { }
            assertEquals(4, channels.size)
        } finally {
            jobs.forEach { it.cancelAndJoin() }
        }
        assertTrue(channels.all { it.closed })
    }
}
