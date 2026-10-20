package dev.herdroid.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import dev.herdroid.connection.HerdrConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Images attached to a prompt. They're copied into the app's cache as soon as they're picked
 * (keyboard-inserted content may lose its read grant later), shrunk if large, and on send
 * uploaded to the host, whose paths go into the prompt text: that's how terminal agents take
 * images (Claude Code turns a pasted image path into an attachment).
 */
object ImageAttachments {
    private const val MAX_SIDE = 2048
    internal const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private val preparation = Semaphore(1)
    private const val PASS_THROUGH_BYTES = 1_500_000
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private val passThrough = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
    )

    private fun dir(context: Context) = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Copies [uri] into the cache, re-encoding big or unusual images as a bounded JPEG. */
    suspend fun prepare(context: Context, uri: Uri): File = preparation.withPermit {
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val source = File.createTempFile("source-", ".tmp", dir(context))
            var result: File? = null
            try {
                resolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { copyBounded(input, it, MAX_SOURCE_BYTES) }
                } ?: error("Couldn't read the image")
                val keepExt = passThrough[resolver.getType(uri)]?.takeIf {
                    source.length() <= PASS_THROUGH_BYTES && fitsAsIs(source)
                }
                val target = File(dir(context), "image-${UUID.randomUUID()}.${keepExt ?: "jpg"}")
                result = target
                if (keepExt != null) {
                    if (!source.renameTo(target)) error("Couldn't store the image")
                } else shrink(source, target)
                target
            } catch (e: Throwable) {
                result?.delete()
                throw e
            } finally { source.delete() }
        }
    }

    internal fun copyBounded(input: InputStream, output: OutputStream, limit: Int) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            if (total > limit) throw IOException("Image exceeds the ${limit / (1024 * 1024)} MiB limit")
            output.write(buffer, 0, count)
        }
    }

    /** Drops attachments left behind by prompts that were never sent. */
    fun pruneStale(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Reuses uploads only while the same API/SSH connection remains active. */
    suspend fun upload(connection: HerdrConnection, files: List<File>, uploads: AttachmentUploads): List<String> {
        val source = connection.api ?: error("Not connected")
        val paths = uploads.upload(source, files) { file ->
            check(connection.api === source) { "Connection changed; retry the send" }
            val bytes = withContext(Dispatchers.IO) {
                file.inputStream().use { input ->
                    java.io.ByteArrayOutputStream().also { copyBounded(input, it, MAX_SOURCE_BYTES) }.toByteArray()
                }
            }
            connection.uploadFile(file.name, bytes, expectedApi = source).getOrThrow()
        }
        check(connection.api === source) { "Connection changed; retry the send" }
        return paths
    }

    /** The prompt text with the uploaded images' paths after it. */
    fun withPaths(text: String, paths: List<String>): String = when {
        paths.isEmpty() -> text
        text.isBlank() -> paths.joinToString(" ")
        else -> text.trimEnd() + "\n\n" + paths.joinToString(" ")
    }

    fun thumbnail(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), size)
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun fitsAsIs(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE && orientation(file) == (0 to false)
    }

    private fun shrink(file: File, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) error("Not an image")
        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, MAX_SIDE) },
        ) ?: error("Couldn't decode the image")
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val (rotation, flipped) = orientation(file)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (flipped) postScale(-1f, 1f)
            postRotate(rotation.toFloat())
        }
        var transformed: Bitmap? = null
        try {
            val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            transformed = bitmap
            target.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) error("Couldn't encode the image")
            }
        } finally {
            transformed?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    /** EXIF describes a horizontal reflection followed by clockwise rotation. */
    internal fun orientation(file: File): Pair<Int, Boolean> = runCatching {
        ExifInterface(file).let { it.rotationDegrees to it.isFlipped }
    }.getOrDefault(0 to false)

    private fun sampleSize(longest: Int, target: Int): Int {
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }
}
