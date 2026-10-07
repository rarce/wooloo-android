package dev.herdroid.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.herdroid.connection.HerdrConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    private const val PASS_THROUGH_BYTES = 1_500_000
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private val passThrough = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
    )

    private fun dir(context: Context) = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Copies [uri] into the cache, re-encoding big or unusual images as a bounded JPEG. */
    suspend fun prepare(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read the image")
        val mime = resolver.getType(uri)
        val keepExt = passThrough[mime]?.takeIf { bytes.size <= PASS_THROUGH_BYTES && fitsAsIs(bytes) }
        val (data, ext) = if (keepExt != null) bytes to keepExt else shrink(bytes) to "jpg"
        File(dir(context), "image-${UUID.randomUUID().toString().take(8)}.$ext").apply { writeBytes(data) }
    }

    /** Drops attachments left behind by prompts that were never sent. */
    fun pruneStale(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Uploads [files] to the host and returns their remote paths; local copies are removed. */
    suspend fun upload(connection: HerdrConnection, files: List<File>): List<String> = files.map { file ->
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        connection.uploadFile(file.name, bytes).getOrThrow().also { file.delete() }
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

    private fun fitsAsIs(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE && orientation(bytes) == 0
    }

    private fun shrink(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) error("Not an image")
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(longest, MAX_SIDE) },
        ) ?: error("Couldn't decode the image")
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(orientation(bytes).toFloat())
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    /** Degrees the image must be rotated to display upright, from its EXIF data. */
    private fun orientation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun sampleSize(longest: Int, target: Int): Int {
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }
}
