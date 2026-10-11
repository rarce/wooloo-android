package dev.herdroid.connection

import dev.herdroid.data.*
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.SessionSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SnapshotGenerationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 10_000)
    fun pendingOldRefreshCannotCommitAcrossApiReplacement() {
        val context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val secrets = SecretStore(context)
        val connection = HerdrConnection(HostProfile(hostname = "old.example", username = "alice"), ConnectionDeps(
            HostRepository(File(directory, "hosts.json")),
            SshKeyManager(File(directory, "keys.json"), secrets), secrets,
            KnownHostsStore(File(directory, "known-hosts.json")), Notifier(context),
        ))
        val oldApi = HerdrApi({ error("No network expected") }, "old", null)
        val newApi = HerdrApi({ error("No network expected") }, "new", null)
        val apiField = HerdrConnection::class.java.getDeclaredField("api").apply { isAccessible = true }
        val lock = requireNotNull(HerdrConnection::class.java.getDeclaredField("remoteStateLock").apply { isAccessible = true }.get(connection))
        val started = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        var worker: Thread? = null
        try {
            apiField.set(connection, oldApi)
            synchronized(lock) {
                // The old response is ready while the reconnect transaction owns the lock.
                worker = Thread {
                    started.countDown()
                    try {
                        connection.applySnapshot(oldApi, SessionSnapshot(version = "old"))
                        finished.complete(Unit)
                    } catch (failure: Throwable) { finished.completeExceptionally(failure) }
                }.apply { start() }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (worker!!.state != Thread.State.BLOCKED && !finished.isDone && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Snapshot commit must serialize with replacement", Thread.State.BLOCKED, worker!!.state)
                apiField.set(connection, newApi)
                connection.applySnapshot(newApi, SessionSnapshot(version = "new"))
            }
            finished.get(2, TimeUnit.SECONDS)
            assertEquals("new", connection.snapshot.value?.version)
        } finally {
            connection.shutdown()
            worker?.join(2000)
        }
    }
}
