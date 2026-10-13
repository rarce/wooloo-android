package dev.herdroid.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class UpdateHttpTest {
    @get:Rule val temp = TemporaryFolder()

    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun release(server: MockWebServer, sha256: String = apkSha, size: Long = apk.size.toLong()) =
        UpdateRelease(2, "0.0.2", server.url("/app.apk").toString(), sha256, size)

    @Test fun missingFeedMeansNothingIsPublished(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(UpdateHttp().fetchFeed(server.url("/update.json").toString()))
        }
    }

    @Test fun feedErrorsAndOversizedFeedsFail(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setBody("x".repeat(UpdateFeed.MAX_FEED_BYTES + 1)))
            val url = server.url("/update.json").toString()
            expectIOException("Update check failed (HTTP 500)") { UpdateHttp().fetchFeed(url) }
            expectIOException("Update feed is too large") { UpdateHttp().fetchFeed(url) }
        }
    }

    @Test fun downloadsAMatchingApkAndReportsProgress(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(apk)))
            val target = temp.root.resolve("updates/update.apk")
            var last = 0f
            UpdateHttp().download(release(server), target) { last = it }
            assertArrayEquals(apk, target.readBytes())
            assertEquals(1f, last)
        }
    }

    @Test fun mismatchedOrOversizedDownloadsAreDeleted(): Unit = runBlocking {
        MockWebServer().use { server ->
            val target = temp.root.resolve("update.apk")
            server.enqueue(MockResponse().setBody(Buffer().write(apk)))
            expectIOException("Download doesn't match its checksum") {
                UpdateHttp().download(release(server, sha256 = "0".repeat(64)), target)
            }
            assertFalse(target.exists())
            server.enqueue(MockResponse().setBody(Buffer().write(apk)))
            expectIOException("Download is larger than announced") {
                UpdateHttp().download(release(server, size = apk.size - 1L), target)
            }
            assertFalse(target.exists())
            server.enqueue(MockResponse().setBody(Buffer().write(apk)))
            expectIOException("Download is incomplete") {
                UpdateHttp().download(release(server, size = apk.size + 1L), target)
            }
            assertFalse(target.exists())
        }
    }

    private suspend fun expectIOException(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected: $message")
        } catch (e: IOException) {
            assertEquals(message, e.message)
        }
    }
}
