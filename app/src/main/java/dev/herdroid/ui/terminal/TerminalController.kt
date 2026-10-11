package dev.herdroid.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.TerminalEvent
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.terminal.ModifierState
import dev.herdroid.terminal.TerminalModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    data class Busy(val reason: String) : PaneState
    /** We were in control and someone else took over. */
    data object TakenOver : PaneState
    data object Offline : PaneState
    data class Closed(val reason: String?) : PaneState
}

/**
 * Owns one pane's live terminal stream for the terminal screen: opens it once the view knows
 * its grid size, keeps the remote size in sync, and reopens it after reconnects.
 */
class TerminalController(
    private val connection: HerdrConnection,
    val paneId: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val model = TerminalModel()
    val modifiers = ModifierState()

    var state by mutableStateOf<PaneState>(PaneState.WaitingForLayout)
        private set
    var readOnly by mutableStateOf(false)
        private set

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
                    ConnectionStatus.Connected -> if (state == PaneState.Offline) open()
                    is ConnectionStatus.Reconnecting, is ConnectionStatus.Failed -> {
                        if (state == PaneState.Live || state == PaneState.Opening) {
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
            open()
            return
        }
        // Debounce: pinch-zoom and keyboard animations produce bursts of sizes.
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(120)
            stream?.resize(this@TerminalController.cols, this@TerminalController.rows, cellW, cellH)
        }
    }

    fun open(takeover: Boolean = false, observeOnly: Boolean = readOnly) {
        if (cols == 0) return
        closeStream()
        readOnly = observeOnly
        if (!connection.isLive) {
            state = PaneState.Offline
            return
        }
        state = PaneState.Opening
        val openingGeneration = generation
        streamJob = scope.launch {
            val opened = runCatching {
                connection.openTerminal(paneId, cols, rows, takeover, observeOnly, scope)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                if (generation != openingGeneration) return@launch
                state = if (connection.isLive) PaneState.Closed(error.message) else PaneState.Offline
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
                                if (generation == openingGeneration) state = PaneState.Live
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
            event.needsTakeover -> PaneState.Busy(event.reason.orEmpty())
            event.wasTakenOver -> PaneState.TakenOver
            !connection.isLive -> PaneState.Offline
            else -> PaneState.Closed(event.reason)
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

    /** Validate ownership at the same moment the frame mutates the model. */
    internal fun applyFrame(expectedGeneration: Long, frame: TerminalEvent.Frame): Boolean = synchronized(model.lock) {
        if (generation != expectedGeneration) return@synchronized false
        model.applyFrame(frame.width, frame.height, frame.full, frame.bytes)
        true
    }
}
