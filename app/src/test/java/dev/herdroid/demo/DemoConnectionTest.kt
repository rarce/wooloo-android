package dev.herdroid.demo

import dev.herdroid.connection.ConnectionDeps
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.connection.Notifier
import dev.herdroid.data.HostLink
import dev.herdroid.data.HostProfile
import dev.herdroid.data.HostRepository
import dev.herdroid.data.KnownHostsStore
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DemoConnectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 20_000)
    fun demoMachineConnectsWithoutNetworkOrCredentials(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val secrets = SecretStore(context)
        val hosts = HostRepository(File(directory, "hosts.json"))
        val knownHosts = KnownHostsStore(File(directory, "known-hosts.json"))
        val profile = HostProfile(label = "Demo machine", hostname = "demo", demo = true).also(hosts::upsert)
        val connection = HerdrConnection(profile, ConnectionDeps(
            hosts, SshKeyManager(File(directory, "keys.json"), secrets), secrets, knownHosts, Notifier(context),
        ))
        try {
            connection.start()
            withTimeout(10_000) { connection.status.first { it == ConnectionStatus.Connected } }
            assertNull(connection.prompt.value)
            assertEquals(3, connection.snapshot.value!!.workspaces.size)
            assertEquals("~/src/api", connection.abbreviatePath("${DemoHerdr.HOME}/src/api"))
            assertTrue(connection.listDirectories("~").getOrThrow().entries.any { it.name == "src" })
            assertEquals("demo-machine", connection.sampleHost().getOrThrow().hostname)
            withTimeout(5_000) { hosts.hosts.first { it.single().lastConnectedAt != null } }
            assertNull(knownHosts.find("demo", 22))
        } finally {
            connection.shutdown()
        }
    }

    @Test fun linksNeverUpdateTheDemoMachine() {
        val demo = HostProfile(label = "Mac", hostname = "demo", username = "alice", demo = true)
        val link = HostLink.parse("herdroid://add-host?host=demo&user=alice&label=Mac")!!
        assertNull(link.findExisting(listOf(demo)))
        assertEquals("Simulated on this phone", demo.target)
    }
}
