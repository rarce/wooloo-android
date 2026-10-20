package dev.herdroid.ssh

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Graceful SSH close may await a peer acknowledgement. Never run it on the caller's thread. */
internal suspend fun CommandChannel.closeBounded(terminateRemote: Boolean = false) = withContext(NonCancellable) {
    coroutineScope {
        val aborted = AtomicBoolean()
        fun abortOnce() {
            if (aborted.compareAndSet(false, true)) runCatching { abort() }
        }
        // Socket writes can ignore interruption. Closing the transport independently
        // also releases a close operation blocked in such a write.
        val watchdog = launch(Dispatchers.IO) {
            delay(CHANNEL_CLOSE_GRACE_MS)
            abortOnce()
        }
        try {
            withTimeout(CHANNEL_CLOSE_GRACE_MS) { interruptibleIo { close(terminateRemote) } }
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { abortOnce() }
        } finally {
            watchdog.cancelAndJoin()
        }
    }
}

internal const val CHANNEL_CLOSE_GRACE_MS = 250L

/** Socket stream writes ignore interruption, so cancellation must also close their transport. */
internal suspend fun <T> abortOnCancellation(abort: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val owner = currentCoroutineContext()
    val finished = CompletableDeferred<Unit>()
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (!owner.isActive) withContext(NonCancellable) {
                // sshj's channel reads react to interruption. Only sacrifice the
                // transport if IO actually remains stuck after cancellation.
                withTimeoutOrNull(CHANNEL_CLOSE_GRACE_MS) { finished.await() }
                if (!finished.isCompleted) runCatching { abort() }
            }
        }
    }
    try { block() } finally {
        finished.complete(Unit)
        closer.cancelAndJoin()
    }
}

internal suspend fun <T> CommandChannel.channelIo(block: () -> T): T =
    abortOnCancellation(::abort) { interruptibleIo(block) }
