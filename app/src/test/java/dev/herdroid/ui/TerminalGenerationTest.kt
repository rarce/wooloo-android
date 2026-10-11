package dev.herdroid.ui

import dev.herdroid.connection.*
import dev.herdroid.data.*
import dev.herdroid.herdr.TerminalEvent
import dev.herdroid.ui.terminal.TerminalController
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
class TerminalGenerationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 10_000)
    fun oldFrameWaitingForModelCannotPaintAfterStreamInvalidation() {
        val context = RuntimeEnvironment.getApplication()
        val directory = temporary.newFolder()
        val secrets = SecretStore(context)
        val connection = HerdrConnection(HostProfile(hostname = "example", username = "alice"), ConnectionDeps(
            HostRepository(File(directory, "hosts.json")),
            SshKeyManager(File(directory, "keys.json"), secrets), secrets,
            KnownHostsStore(File(directory, "known-hosts.json")), Notifier(context),
        ))
        val controller = TerminalController(connection, "pane")
        val started = CountDownLatch(1)
        val finished = CompletableFuture<Boolean>()
        var worker: Thread? = null
        try {
            assertTrue(controller.applyFrame(0, frame("current", full = true)))
            synchronized(controller.model.lock) {
                worker = Thread {
                    started.countDown()
                    try { finished.complete(controller.applyFrame(0, frame("obsolete", full = false))) }
                    catch (failure: Throwable) { finished.completeExceptionally(failure) }
                }.apply { start() }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (worker!!.state != Thread.State.BLOCKED && !finished.isDone && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, worker!!.state)
                controller.dispose()
            }
            assertFalse("A frame queued before disposal must be rejected at model commit", finished.get(2, TimeUnit.SECONDS))
            assertFalse(controller.model.screenText().contains("obsolete"))
            assertTrue(controller.model.screenText().contains("current"))
        } finally {
            controller.dispose()
            connection.shutdown()
            worker?.join(2000)
        }
    }

    private fun frame(text: String, full: Boolean) = TerminalEvent.Frame(1, 80, 24, full, text.toByteArray())
}
