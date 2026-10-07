package dev.herdroid.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirListingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesPathAndSortsEntries() {
        val listing = DirListing.parse("/home/me\nd/zeta\ng/Api\nd/.config\njunk\n")!!
        assertEquals("/home/me", listing.path)
        assertEquals(listOf("Api", ".config", "zeta").sortedWith(String.CASE_INSENSITIVE_ORDER), listing.entries.map { it.name })
        assertEquals(true, listing.entries.first { it.name == "Api" }.isRepo)
        assertEquals("/home", listing.parent)
        assertEquals("/home/me/x", listing.child("x"))
    }

    @Test
    fun rootHasNoParent() {
        val listing = DirListing.parse("/\nd/usr\n")!!
        assertNull(listing.parent)
        assertEquals("/usr", listing.child("usr"))
        assertEquals("/", DirListing.parse("/home\n")!!.parent)
    }

    @Test
    fun rejectsOutputWithoutAbsolutePath() {
        assertNull(DirListing.parse(""))
        assertNull(DirListing.parse("sh: cd: nope\n"))
    }

    @Test
    fun scriptListsDirectoriesAndFallsBackToExistingAncestor() {
        val root = tmp.newFolder("root")
        File(root, "plain").mkdir()
        File(root, "repo/.git").mkdirs()
        File(root, ".hidden").mkdir()
        File(root, "it's spaced").mkdir()
        File(root, "file.txt").writeText("x")

        val listing = DirListing.parse(run(RemoteCommand.listDirectories(File(root, "missing/deeper").path)))!!
        assertEquals(root.canonicalPath, File(listing.path).canonicalPath)
        assertEquals(
            setOf(RemoteDir("plain", false), RemoteDir("repo", true), RemoteDir(".hidden", false), RemoteDir("it's spaced", false)),
            listing.entries.toSet(),
        )
    }

    private fun run(command: String): String {
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        return process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }
}
