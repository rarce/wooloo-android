package dev.herdroid.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class QuotaHttpTest {
    @Test(timeout = 10_000) fun cancelledHttpCallDoesNotWaitForServerResponse(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val start = System.nanoTime()
            try {
                withTimeout(500) { QuotaHttp.Default.get(server.url("/usage").toString(), emptyMap()) }
                fail("Expected cancellation")
            } catch (_: TimeoutCancellationException) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
    }

    @Test fun responseBodyHasAnEnforcedByteLimit(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(512 * 1024 + 1)))
            server.start()
            try { QuotaHttp.Default.get(server.url("/usage").toString(), emptyMap()); fail("Expected limit") }
            catch (e: IOException) { assertEquals("Usage response is too large", e.message) }
        }
    }
}
