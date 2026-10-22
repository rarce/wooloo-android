package dev.herdroid.ssh

import kotlinx.coroutines.*

/**
 * Channel work that outlives the coroutine giving up on it: graceful closes still awaiting the
 * peer, and opens abandoned by a fast pane switch. One channel must never cost the shared
 * transport, so this work finishes in the background instead of aborting it.
 */
internal val channelReaper = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Graceful SSH close may await a peer acknowledgement. Never run it on the caller's thread.
 * A prompt acknowledgement completes within [graceMs]; a slower one (mobile RTT, a Cloudflare
 * tunnel, a bridge still exiting) continues in the background. The transport is aborted only
 * when the close stays stuck and the transport itself fails its health probe.
 */
internal suspend fun CommandChannel.closeBounded(
    terminateRemote: Boolean = false,
    graceMs: Long = CHANNEL_CLOSE_GRACE_MS,
    stuckMs: Long = CHANNEL_STUCK_MS,
) = withContext(NonCancellable) {
    val closing = channelReaper.launch { runCatching { close(terminateRemote) } }
    if (withTimeoutOrNull(graceMs) { closing.join() } == null) {
        channelReaper.launch { abortIfStuck(closing, ::isTransportHealthy, ::abort, stuckMs) }
    }
}

/**
 * Waits for [work]. Socket writes can ignore interruption, so while [work] stays blocked past
 * [stuckMs] the transport is probed, and closed only once it no longer answers: that releases
 * the stuck write (#12) without breaking a healthy connection.
 */
internal suspend fun abortIfStuck(work: Job, isHealthy: suspend () -> Boolean, abort: () -> Unit, stuckMs: Long) {
    while (withTimeoutOrNull(stuckMs) { work.join() } == null) {
        if (!isHealthy()) {
            runCatching { abort() }
            return
        }
    }
}

internal const val CHANNEL_CLOSE_GRACE_MS = 250L
internal const val CHANNEL_STUCK_MS = 10_000L

/** Socket stream writes ignore interruption, so cancellation may also have to close their transport. */
internal suspend fun <T> abortOnCancellation(
    abort: () -> Unit,
    isHealthy: suspend () -> Boolean = { false },
    stuckMs: Long = CHANNEL_STUCK_MS,
    block: suspend () -> T,
): T = coroutineScope {
    val owner = currentCoroutineContext()
    val finished = Job()
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (!owner.isActive) withContext(NonCancellable) {
                // sshj's channel reads react to interruption. Only sacrifice the transport
                // if IO remains stuck after cancellation and the transport stopped answering.
                abortIfStuck(finished, isHealthy, abort, stuckMs)
            }
        }
    }
    try { block() } finally {
        finished.complete()
        closer.cancelAndJoin()
    }
}

internal suspend fun <T> CommandChannel.channelIo(stuckMs: Long = CHANNEL_STUCK_MS, block: () -> T): T =
    abortOnCancellation(::abort, ::isTransportHealthy, stuckMs) { interruptibleIo(block) }
