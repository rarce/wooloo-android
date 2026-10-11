package dev.herdroid.ui

import dev.herdroid.ui.components.AttachmentUploads
import dev.herdroid.ui.components.ImageAttachments
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*

class AttachmentUploadsTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun retryKeepsFilesAndReusesOnlyCompletedUploads(): Unit = runBlocking {
        val files = listOf(directory.newFile("one"), directory.newFile("two"))
        files.forEach { it.writeText("test-image") }
        val uploads = AttachmentUploads()
        val source = Any()
        val calls = mutableListOf<String>()
        var failSecond = true
        val send: suspend (File) -> String = { file ->
            calls += file.name
            if (file.name == "two" && failSecond) throw IOException("network failure")
            "/tmp/${file.name}"
        }
        try { uploads.upload(source, files, send); fail("Expected failure") }
        catch (_: IOException) { }
        assertTrue(files.all { it.exists() })
        failSecond = false
        assertEquals(listOf("/tmp/one", "/tmp/two"), uploads.upload(source, files, send))
        assertEquals(listOf("one", "two", "two"), calls)
        // A prompt failure after all uploads also retains both local copies.
        uploads.upload(source, files, send)
        assertEquals(3, calls.size)
        assertTrue(files.all { it.exists() })
        uploads.upload(Any(), files, send)
        assertEquals(listOf("one", "two", "two", "one", "two"), calls)
    }

    @Test fun boundedCopyRejectsOversizeWithoutReadingTheWholeSource() {
        var bytesRead = 0
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { bytesRead += len; return len }
        }
        val output = ByteArrayOutputStream()
        try { ImageAttachments.copyBounded(input, output, 1024); fail("Expected size limit") }
        catch (_: IOException) { }
        assertTrue(bytesRead <= 1024 + 8192)
        assertTrue(output.size() <= 1024)
    }

    @Test fun boundedCopyPreservesAcceptedInput() {
        val bytes = ByteArray(1024) { it.toByte() }
        val output = ByteArrayOutputStream()
        ImageAttachments.copyBounded(bytes.inputStream(), output, 1024)
        assertArrayEquals(bytes, output.toByteArray())
    }
}
