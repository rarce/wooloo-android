package dev.herdroid.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestartableJobTest {
    @Test
    fun rapidRestartsWaitForCleanupAndNeverOverlap() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var active = 0
        var maximum = 0
        var starts = 0
        val lifecycle = RestartableJob(scope, interrupt = {}) {
            active++
            starts++
            maximum = maxOf(maximum, active)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(100); active-- }
            }
        }
        lifecycle.restart()
        runCurrent()
        assertEquals(1, active)
        lifecycle.restart()
        runCurrent()
        repeat(100) { lifecycle.restart() }
        runCurrent()
        assertEquals(1, starts)
        advanceUntilIdle()
        assertEquals(1, maximum)
        assertEquals(1, active)
        scope.cancel()
        advanceUntilIdle()
        assertEquals(0, active)
    }

    @Test
    fun finishedLoopCanBeRestartedAndShutdownInterruptsResources() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var attempts = 0
        var interrupts = 0
        val lifecycle = RestartableJob(scope, interrupt = { interrupts++ }) { attempts++ }
        repeat(3) { lifecycle.restart(); runCurrent() }
        assertEquals(3, attempts)
        scope.cancel()
        advanceUntilIdle()
        assertTrue(interrupts >= 4)
        lifecycle.restart()
        runCurrent()
        assertEquals(3, attempts)
    }
}
