package dev.herdroid.ui.hosts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundEditTest {
    @Test fun blockedStorageKeepsOwnerResponsiveAndDropsDoubleTap() = runBlocking {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "edit-ui") }.asCoroutineDispatcher().use { ui ->
            val scope = CoroutineScope(SupervisorJob() + ui)
            val edit = BackgroundEdit()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val called = AtomicInteger()
            val completed = CompletableDeferred<String>()
            try {
                withContext(ui) {
                    edit.submit(scope, "failure", work = {
                        assertFalse(Thread.currentThread().name.startsWith("edit-ui"))
                        called.incrementAndGet()
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        "saved"
                    }, success = {
                        assertTrue(Thread.currentThread().name.startsWith("edit-ui"))
                        completed.complete(it)
                    })
                    edit.submit(scope, "failure", work = { called.incrementAndGet() })
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                withTimeout(1000) { withContext(ui) { assertTrue(edit.busy) } }
                assertEquals(1, called.get())
                release.countDown()
                assertEquals("saved", withTimeout(5000) { completed.await() })
                withContext(ui) { assertFalse(edit.busy) }
            } finally {
                release.countDown()
                scope.cancel()
            }
        }
    }

    @Test fun failedMutationIsRetryableAndCancelledOwnerNeverNavigates() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            val scope = CoroutineScope(SupervisorJob() + ui)
            val edit = BackgroundEdit()
            val failed = CountDownLatch(1)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val successes = AtomicInteger()
            try {
                withContext(ui) {
                    edit.submit(scope, "Draft retained", work = { failed.countDown(); throw IOException("secret diagnostic") },
                        success = { successes.incrementAndGet() })
                }
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                // Queue behind the completion dispatched to the owner after IO exits.
                withTimeout(5000) {
                    while (withContext(ui) { edit.busy }) kotlinx.coroutines.yield()
                }
                withContext(ui) {
                    assertEquals("Draft retained", edit.error)
                    edit.submit(scope, "failure", work = {
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        finished.countDown()
                    }, success = { successes.incrementAndGet() })
                    assertNull(edit.error)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                scope.cancel()
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                withTimeout(5000) { while (withContext(ui) { edit.busy }) kotlinx.coroutines.yield() }
                assertEquals(0, successes.get())
            } finally { release.countDown(); scope.cancel() }
        }
    }
}
