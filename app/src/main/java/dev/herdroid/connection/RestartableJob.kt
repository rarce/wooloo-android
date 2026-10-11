package dev.herdroid.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Serializes restarts and waits for the previous loop and its children to finish. */
internal class RestartableJob(
    scope: CoroutineScope,
    interrupt: () -> Unit,
    block: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            supervisorScope {
                var active: Job? = null
                try {
                    for (request in requests) {
                        active?.cancel()
                        interrupt()
                        active?.join()
                        active = launch { block() }
                    }
                } finally {
                    active?.cancel()
                    interrupt()
                    withContext(NonCancellable) { active?.join() }
                    requests.close()
                }
            }
        }
    }

    fun restart() { requests.trySend(Unit) }
}
