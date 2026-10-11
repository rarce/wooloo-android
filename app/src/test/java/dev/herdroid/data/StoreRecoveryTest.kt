package dev.herdroid.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StoreRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun invalidProfilesArePreservedAcrossSubsequentSave() {
        val file = directory.newFile("hosts.json").apply { writeText("[{broken") }
        val repository = HostRepository(file)
        assertTrue(repository.hosts.value.isEmpty())
        repository.upsert(HostProfile(id = "new", hostname = "example.com"))
        val recovery = directory.root.listFiles()!!.single { it.name.startsWith("hosts.json.corrupt-") }
        assertEquals("[{broken", recovery.readText())
        assertEquals("new", HostRepository(file).hosts.value.single().id)
    }

    @Test
    fun corruptKnownHostsArePreserved() {
        val file = directory.newFile("known_hosts.json").apply { writeText("not-json") }
        val store = KnownHostsStore(file)
        store.trust(KnownHost("example.com", 22, "ssh-ed25519", "SHA256:test"))
        assertNotNull(KnownHostsStore(file).find("EXAMPLE.COM", 22))
        assertEquals("not-json", directory.root.listFiles()!!.single {
            it.name.startsWith("known_hosts.json.corrupt-")
        }.readText())
    }

    @Test(timeout = 10_000)
    fun concurrentConnectionsAndEditsRemainConsistentAfterReload() {
        val file = File(directory.root, "hosts.json")
        val repository = HostRepository(file)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (0 until 40).map { i -> pool.submit {
                repository.upsert(HostProfile(id = "$i", label = "host-$i"))
                repeat(3) { repository.markConnected("$i") }
            } }
            tasks.forEach { it.get(8, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val reloaded = HostRepository(file).hosts.value
        assertEquals(40, reloaded.size)
        assertEquals(repository.hosts.value, reloaded)
        assertTrue(reloaded.all { it.lastConnectedAt != null })
        assertTrue(directory.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun failedCommitDoesNotPublishUnpersistedState() {
        val file = File(directory.root, "hosts.json")
        val repository = HostRepository(file)
        file.mkdir()
        File(file, "block-replacement").writeText("preserve")
        try { repository.upsert(HostProfile(id = "new")); fail("Expected write failure") }
        catch (_: IOException) { }
        assertTrue(repository.hosts.value.isEmpty())
        assertEquals("preserve", File(file, "block-replacement").readText())
    }

    @Test
    fun unreadableStoreIsNotSilentlyReplaced() {
        val file = directory.newFolder("hosts.json")
        try { HostRepository(file); fail("Expected IO failure") }
        catch (_: IOException) { }
        assertTrue(file.isDirectory)
    }

    @Test
    fun savingAnOlderEditorSnapshotDoesNotEraseConnectionTimestamp() {
        val repository = HostRepository(File(directory.root, "hosts.json"))
        val editorSnapshot = HostProfile(id = "host", label = "old")
        repository.upsert(editorSnapshot)
        repository.markConnected("host")
        val connectedAt = repository.get("host")!!.lastConnectedAt
        repository.upsert(editorSnapshot.copy(label = "edited"))
        assertEquals("edited", repository.get("host")!!.label)
        assertEquals(connectedAt, repository.get("host")!!.lastConnectedAt)
    }
}
