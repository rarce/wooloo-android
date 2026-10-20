package dev.herdroid.ui

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import dev.herdroid.ui.components.ImageAttachments
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun readsAllEightExifOrientationsIncludingReflections() {
        val expected = listOf(
            0 to false, 0 to true, 180 to false, 180 to true,
            270 to true, 90 to false, 90 to true, 270 to false,
        )
        expected.forEachIndexed { index, transform ->
            val file = directory.newFile("orientation-${index + 1}.jpg")
            val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
            try {
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { bitmap.recycle() }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, (index + 1).toString())
                saveAttributes()
            }
            assertEquals("EXIF ${index + 1}", transform, ImageAttachments.orientation(file))
        }
    }

    @Test fun malformedMetadataUsesIdentityTransform() {
        val file = directory.newFile("invalid.jpg").apply { writeText("not an image") }
        assertEquals(0 to false, ImageAttachments.orientation(file))
    }
}
