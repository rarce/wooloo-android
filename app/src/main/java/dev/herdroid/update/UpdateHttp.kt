package dev.herdroid.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches the update feed and downloads release APKs; cancelling the coroutine cancels the call. */
class UpdateHttp(private val client: OkHttpClient = defaultClient) {

    /** The published release; unavailable feeds, including HTTP 404, fail the check. */
    suspend fun fetchFeed(url: String): UpdateRelease {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = send(request) { response ->
            if (!response.isSuccessful) throw IOException("Update check failed (HTTP ${response.code})")
            val output = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > UpdateFeed.MAX_FEED_BYTES) throw IOException("Update feed is too large")
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
        return UpdateFeed.parse(body)
    }

    /**
     * Downloads [release] into [target], checking its size and SHA-256 as it streams. [target] is
     * deleted unless the download completes and matches.
     */
    suspend fun download(release: UpdateRelease, target: File, onProgress: (Float) -> Unit = {}) {
        target.parentFile?.mkdirs()
        target.delete()
        val request = Request.Builder().url(release.url).build()
        try {
            send(request) { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                response.body?.byteStream()?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > release.size) throw IOException("Download is larger than announced")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(total.toFloat() / release.size)
                        }
                    }
                }
                if (total != release.size) throw IOException("Download is incomplete")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != release.sha256) throw IOException("Download doesn't match its checksum")
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private suspend fun <T> send(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
