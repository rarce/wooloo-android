package dev.herdroid.herdr

import dev.herdroid.ui.components.ImageAttachments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadFileTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun scriptStoresStdinInPrivateTempDirAndPrintsPath() {
        val temp = tmp.newFolder("t")
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val process = ProcessBuilder("/bin/sh", "-c", RemoteCommand.uploadFile("image-1.png"))
            .apply { environment()["TMPDIR"] = temp.path + "/" }
            .start()
        process.outputStream.use { it.write(bytes) }
        val path = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())

        val file = File(path)
        assertTrue(path, file.path.startsWith(temp.path + "/herdroid-"))
        assertEquals("image-1.png", file.name)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun imagePathsFollowThePromptText() {
        assertEquals("look", ImageAttachments.withPaths("look", emptyList()))
        assertEquals("/t/a.png /t/b.jpg", ImageAttachments.withPaths("  ", listOf("/t/a.png", "/t/b.jpg")))
        assertEquals("what's wrong?\n\n/t/a.png", ImageAttachments.withPaths("what's wrong?\n", listOf("/t/a.png")))
    }
}
