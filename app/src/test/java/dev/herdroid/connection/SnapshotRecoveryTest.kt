package dev.herdroid.connection

import dev.herdroid.data.HostProfile
import dev.herdroid.data.HostRepository
import dev.herdroid.data.KnownHostsStore
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyManager
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.ssh.CommandChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SnapshotRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun connection(): HerdrConnection {
        val context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val secrets = SecretStore(context)
        return HerdrConnection(HostProfile(hostname = "example.invalid", username = "alice"), ConnectionDeps(
            HostRepository(File(directory, "hosts.json")),
            SshKeyManager(File(directory, "keys.json"), secrets), secrets,
            KnownHostsStore(File(directory, "known-hosts.json")), Notifier(context),
        ))
    }

    private fun install(connection: HerdrConnection, api: HerdrApi) {
        HerdrConnection::class.java.getDeclaredField("api").apply { isAccessible = true }.set(connection, api)
    }

    private fun channel(version: String) = object : CommandChannel {
        override val stdout = "{\"result\":{\"snapshot\":{\"version\":\"$version\"}}}\n".byteInputStream()
        override val stderr = byteArrayOf().inputStream()
        override val stdin = ByteArrayOutputStream()
        override val isOpen = true
        override fun close() = Unit
    }

    @Test(timeout = 10_000)
    fun refreshFailurePreservesSnapshotAndConnectionThenRetryClearsStaleness(): Unit = runBlocking {
        val connection = connection()
        var fail = true
        val api = HerdrApi({
            if (fail) throw IOException("private remote diagnostic")
            channel("fresh")
        }, "default", null)
        try {
            install(connection, api)
            @Suppress("UNCHECKED_CAST")
            val status = HerdrConnection::class.java.getDeclaredField("_status").apply { isAccessible = true }
                .get(connection) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionStatus>
            status.value = ConnectionStatus.Connected
            connection.applySnapshot(api, SessionSnapshot(version = "cached"))
            assertFalse(connection.snapshotHealth.value.stale)
            assertTrue(connection.refreshSnapshot().isFailure)
            assertEquals("cached", connection.snapshot.value?.version)
            assertEquals(ConnectionStatus.Connected, connection.status.value)
            assertTrue(connection.snapshotHealth.value.stale)
            assertNotNull(connection.snapshotHealth.value.error)
            assertFalse(connection.snapshotHealth.value.error!!.contains("private"))
            assertFalse(connection.snapshotHealth.value.refreshing)
            fail = false
            assertTrue(connection.refreshSnapshot().isSuccess)
            assertEquals("fresh", connection.snapshot.value?.version)
            assertEquals(SnapshotHealth(stale = false), connection.snapshotHealth.value)
        } finally { connection.shutdown() }
    }

    @Test(timeout = 10_000)
    fun obsoleteApiFailureCannotMarkNewSnapshotStale(): Unit = runBlocking {
        val connection = connection()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val oldApi = HerdrApi({ entered.complete(Unit); proceed.await(); throw IOException("old") }, "old", null)
        val newApi = HerdrApi({ channel("new") }, "new", null)
        try {
            install(connection, oldApi)
            connection.applySnapshot(oldApi, SessionSnapshot(version = "old"))
            val pending = async { connection.refreshSnapshot() }
            entered.await()
            assertTrue(connection.snapshotHealth.value.refreshing)
            install(connection, newApi)
            connection.applySnapshot(newApi, SessionSnapshot(version = "new"))
            proceed.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals("new", connection.snapshot.value?.version)
            assertEquals(SnapshotHealth(stale = false), connection.snapshotHealth.value)
        } finally { connection.shutdown() }
    }

    @Test(timeout = 10_000)
    fun callerCancellationNeverBecomesARefreshError(): Unit = runBlocking {
        val connection = connection()
        val entered = CompletableDeferred<Unit>()
        val api = HerdrApi({ entered.complete(Unit); awaitCancellation() }, "default", null)
        try {
            install(connection, api)
            connection.applySnapshot(api, SessionSnapshot(version = "cached"))
            val pending = async { connection.refreshSnapshot() }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals("cached", connection.snapshot.value?.version)
            assertEquals(SnapshotHealth(stale = false), connection.snapshotHealth.value)
        } finally { connection.shutdown() }
    }
}
