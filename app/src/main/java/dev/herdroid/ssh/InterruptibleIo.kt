package dev.herdroid.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** sshj translates thread interruption to InterruptedIOException, not InterruptedException. */
internal suspend fun <T> interruptibleIo(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    throw e
}
