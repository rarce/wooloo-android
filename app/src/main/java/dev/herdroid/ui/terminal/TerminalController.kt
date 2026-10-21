package dev.herdroid.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.ui.components.bannerOffersRecovery
import dev.herdroid.herdr.TerminalEvent
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.terminal.ModifierState
import dev.herdroid.terminal.TerminalModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

sealed interface PaneState {
    data object WaitingForLayout : PaneState
    data object Opening : PaneState
    data object Live : PaneState
    /** Another client controls this pane; we can take it over. */
    data class Busy(val reason: String, val afterReconnect: Boolean = false) : PaneState
    /** We were in control and someone else took over. */
    data object TakenOver : PaneState
    data object Offline : PaneState
    data class Closed(val reason: String?, val interrupted: Boolean = false) : PaneState
}

/**
 * What the pane overlay shows. The connection banner owns host problems (`Reconnecting`,
 * `Failed`), so the overlay offers actions only while the host is connected: never two
 * competing recovery surfaces. While the host is away the pane waits and reopens by itself.
 */
internal sealed interface PaneRecovery {
    data object None : PaneRecovery
    data object Loading : PaneRecovery
    data class WaitingForHost(val failed: Boolean) : PaneRecovery
    /** A pane-level problem on a healthy host: takeover, watch, reconnect the stream. */
    data class Actions(val state: PaneState) : PaneRecovery
}

internal fun recoveryUi(status: ConnectionStatus, state: PaneState): PaneRecovery {
    val hostAway = status.bannerOffersRecovery
    val waiting = PaneRecovery.WaitingForHost(failed = status is ConnectionStatus.Failed)
    return when (state) {
        PaneState.Live -> PaneRecovery.None
        PaneState.WaitingForLayout, PaneState.Opening -> if (hostAway) waiting else PaneRecovery.Loading
        PaneState.Offline -> if (status == ConnectionStatus.Connected) PaneRecovery.Loading else waiting
        is PaneState.Busy, PaneState.TakenOver, is PaneState.Closed ->
            if (status == ConnectionStatus.Connected) PaneRecovery.Actions(state) else waiting
    }
}

/**
 * Owns one pane's live terminal stream for the terminal screen: opens it once the view knows
 * its grid size, keeps the remote size in sync, and reopens it after reconnects.
 */
internal interface TerminalConnection {
    val status: StateFlow<ConnectionStatus>
    val isLive: Boolean
    suspend fun openTerminal(paneId: String, cols: Int, rows: Int, takeover: Boolean,
                             readOnly: Boolean, scope: CoroutineScope): TerminalStream
}

class TerminalController internal constructor(
    private val connection: TerminalConnection,
    val paneId: String,
) {
    constructor(connection: HerdrConnection, paneId: String) : this(object : TerminalConnection {
        override val status get() = connection.status
        override val isLive get() = connection.isLive
        override suspend fun openTerminal(paneId: String, cols: Int, rows: Int, takeover: Boolean,
                                          readOnly: Boolean, scope: CoroutineScope) =
            connection.openTerminal(paneId, cols, rows, takeover, readOnly, scope)
    }, paneId)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val model = TerminalModel()
    val modifiers = ModifierState()

    var state by mutableStateOf<PaneState>(PaneState.WaitingForLayout)
        private set
    var readOnly by mutableStateOf(false)
        private set

    private var recovering = false
    private var retries = 0
    private var hostWait: Job? = null
    private var stream: TerminalStream? = null
    private var streamJob: Job? = null
    @Volatile private var generation = 0L
    private var resizeJob: Job? = null
    private var cols = 0
    private var rows = 0
    private var cellW = 0
    private var cellH = 0

    init {
        scope.launch {
            connection.status.collect { status ->
                when (status) {
                    // A conflated Reconnecting→Connected may never be observed as Reconnecting,
                    // leaving an interrupted stream behind: reopen that too.
                    ConnectionStatus.Connected -> if (state == PaneState.Offline ||
                        (state as? PaneState.Closed)?.interrupted == true) open(recovery = true)
                    is ConnectionStatus.Reconnecting, is ConnectionStatus.Failed -> {
                        if (state == PaneState.Live || state == PaneState.Opening ||
                            (state as? PaneState.Closed)?.interrupted == true) {
                            closeStream()
                            state = PaneState.Offline
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun onGridSize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val first = this.cols == 0
        this.cols = cols
        this.rows = rows
        cellW = cellWidthPx
        cellH = cellHeightPx
        if (first || state == PaneState.WaitingForLayout) {
            // A pane switch storm creates and disposes controllers quickly: don't open a
            // channel for a pane that is gone again within the debounce.
            start(takeover = false, observeOnly = readOnly, recovery = false, delayMs = OPEN_DEBOUNCE_MS)
            return
        }
        // Debounce: pinch-zoom and keyboard animations produce bursts of sizes.
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(120)
            stream?.resize(this@TerminalController.cols, this@TerminalController.rows, cellW, cellH)
        }
    }

    fun open(takeover: Boolean = false, observeOnly: Boolean = readOnly, recovery: Boolean = false) {
        retries = 0
        start(takeover, observeOnly, recovery, delayMs = 0)
    }

    private fun start(takeover: Boolean, observeOnly: Boolean, recovery: Boolean, delayMs: Long) {
        readOnly = observeOnly
        if (cols == 0) return
        closeStream()
        hostWait?.cancel()
        recovering = recovery
        if (!connection.isLive) {
            waitForHost()
            return
        }
        state = PaneState.Opening
        val openingGeneration = generation
        streamJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val opened = runCatching {
                connection.openTerminal(paneId, cols, rows, takeover, observeOnly, scope)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != openingGeneration) return@launch
                retryOrClose(error.message)
                return@launch
            }
            if (generation != openingGeneration) {
                opened.close()
                return@launch
            }
            stream = opened
            launch(Dispatchers.Default) {
                opened.events.collect { event ->
                    when (event) {
                        is TerminalEvent.Frame -> {
                            if (!applyFrame(openingGeneration, event)) return@collect
                            if (state != PaneState.Live) scope.launch {
                                if (generation == openingGeneration) {
                                    recovering = false
                                    retries = 0
                                    state = PaneState.Live
                                }
                            }
                        }
                        is TerminalEvent.Closed -> scope.launch {
                            if (generation == openingGeneration) onClosed(event)
                        }
                    }
                }
            }
        }
    }

    private fun onClosed(event: TerminalEvent.Closed) {
        stream = null
        state = when {
            event.needsTakeover -> PaneState.Busy(event.reason.orEmpty(), afterReconnect = recovering)
            event.wasTakenOver -> PaneState.TakenOver
            event.interrupted -> return retryOrClose(event.reason)
            else -> PaneState.Closed(event.reason, interrupted = event.interrupted)
        }
    }

    /**
     * A pane stream that failed to open or ended without herdr closing it is a pane problem
     * while the host is connected: retry it briefly before offering Reconnect. Retries never
     * take over another client's control.
     */
    private fun retryOrClose(reason: String?) {
        when {
            !connection.isLive -> waitForHost()
            retries < RETRY_DELAYS_MS.size ->
                start(takeover = false, observeOnly = readOnly, recovery = true, delayMs = RETRY_DELAYS_MS[retries++])
            else -> state = PaneState.Closed(reason, interrupted = true)
        }
    }

    /** The status collector can miss a conflated Reconnecting→Connected, so wait for it here too. */
    private fun waitForHost() {
        state = PaneState.Offline
        hostWait?.cancel()
        hostWait = scope.launch {
            connection.status.first { it == ConnectionStatus.Connected }
            if (state == PaneState.Offline) open(recovery = true)
        }
    }

    fun input(data: String) {
        stream?.input(data)
    }

    fun tryInput(data: String): Boolean = stream?.tryInput(data) ?: false

    fun scroll(up: Boolean, lines: Int, column: Int, row: Int) {
        stream?.scroll(up, lines, column, row)
    }

    fun tap(column: Int, row: Int) {
        stream?.click(column, row)
    }

    fun setObserveOnly(observe: Boolean) {
        if (observe != readOnly) open(takeover = false, observeOnly = observe)
    }

    private fun closeStream() {
        synchronized(model.lock) { generation++ }
        streamJob?.cancel()
        stream?.close()
        stream = null
    }

    fun dispose() {
        closeStream()
        scope.cancel()
    }

    private companion object {
        const val OPEN_DEBOUNCE_MS = 150L
        val RETRY_DELAYS_MS = longArrayOf(250, 1_000, 3_000)
    }

    /** Validate ownership at the same moment the frame mutates the model. */
    internal fun applyFrame(expectedGeneration: Long, frame: TerminalEvent.Frame): Boolean = synchronized(model.lock) {
        if (generation != expectedGeneration) return@synchronized false
        model.applyFrame(frame.width, frame.height, frame.full, frame.bytes)
        true
    }
}
