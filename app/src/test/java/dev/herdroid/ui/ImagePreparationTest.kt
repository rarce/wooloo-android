package dev.herdroid.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.herdroid.ui.components.ImageAttachments
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ImagePreparationTest {
    @Test fun largeImageIsReducedAndSourceCopyIsRemoved(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("test-image", ".png", app.cacheDir)
        val bitmap = Bitmap.createBitmap(3072, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val result = ImageAttachments.prepare(app, Uri.fromFile(source))
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(result.path, bounds)
                assertEquals(2048, bounds.outWidth)
                assertTrue(bounds.outHeight > 0)
                assertTrue(result.parentFile!!.listFiles()!!.none { it.name.startsWith("source-") })
            } finally { result.delete() }
        } finally { source.delete() }
    }

    @Test fun malformedImageLeavesNoPartialAttachment(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("test-image", ".png", app.cacheDir).apply { writeText("not an image") }
        val dir = File(app.cacheDir, "attachments")
        val before = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        try {
            try { ImageAttachments.prepare(app, Uri.fromFile(source)); fail("Expected decode failure") }
            catch (_: IllegalStateException) { }
            assertEquals(before, dir.listFiles()?.map { it.name }?.toSet() ?: emptySet<String>())
        } finally { source.delete() }
    }
}
