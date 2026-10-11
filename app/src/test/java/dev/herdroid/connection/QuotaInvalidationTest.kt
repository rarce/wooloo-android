package dev.herdroid.connection

import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.AgentQuotaFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaInvalidationTest {
    private val signedIn = AgentQuotaFiles(claudeInstalled = true,
        claudeCredentials = """{"claudeAiOauth":{"accessToken":"old-token"}}""")
    private val usage = """{"five_hour":{"utilization":40}}"""

    @Test fun resetDoesNotReuseOldCredentialOrKeychainDelay(): Unit = runBlocking {
        var files = signedIn
        val keychain = mutableListOf<Boolean>()
        var calls = 0
        val reader = AgentQuotaReader({ keychain += it; files }, QuotaHttp { _, _ -> calls++; 200 to usage })
        assertNotNull(reader.read(0)[AgentProvider.CLAUDE]!!.getOrThrow())
        reader.reset()
        files = AgentQuotaFiles(claudeInstalled = true)
        assertTrue(reader.read(1)[AgentProvider.CLAUDE]!!.isFailure)
        assertEquals(1, calls)
        assertEquals(listOf(true, true), keychain)
    }

    @Test fun detachedInFlightReadCannotRestoreClearedCache(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var files = signedIn
        var calls = 0
        val reader = AgentQuotaReader({ files }, QuotaHttp { _, _ ->
            calls++; entered.complete(Unit); finish.await(); 200 to usage
        })
        val old = async { reader.read(0) }
        entered.await()
        reader.reset()
        files = AgentQuotaFiles(claudeInstalled = true)
        finish.complete(Unit)
        old.await()
        assertTrue(reader.read(1)[AgentProvider.CLAUDE]!!.isFailure)
        assertEquals(1, calls)
    }

    @Test fun invalidatedMonitorDiscardsEvenNonCooperativeOldResult(): Unit = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val reader = AgentQuotaReader({ signedIn }, QuotaHttp { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { finish.await() }
            200 to usage
        })
        val monitor = AgentQuotaMonitor(backgroundScope, reader, clock = { 0 })
        monitor.acquire()
        entered.await()
        monitor.invalidate()
        finish.complete(Unit)
        runCurrent()
        assertFalse(monitor.loaded.value)
        assertTrue(monitor.states.value.isEmpty())
        monitor.release()
    }

    @Test fun lastViewerReleaseDropsCredentialBeforeNextAcquisition(): Unit = runTest {
        var files = signedIn
        var calls = 0
        val reader = AgentQuotaReader({ files }, QuotaHttp { _, _ -> calls++; 200 to usage })
        val monitor = AgentQuotaMonitor(backgroundScope, reader, intervalMs = 100, clock = { testScheduler.currentTime })
        monitor.acquire()
        runCurrent()
        assertEquals(1, calls)
        monitor.release()
        files = AgentQuotaFiles(claudeInstalled = true)
        advanceTimeBy(100)
        monitor.acquire()
        runCurrent()
        assertEquals(1, calls)
        assertNotNull(monitor.states.value[AgentProvider.CLAUDE]!!.failure)
        monitor.release()
    }
}
