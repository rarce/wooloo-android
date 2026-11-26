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
import kotlin.math.max
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
        is PaneState.Busy, is PaneState.Closed ->
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
    /** Watching because another client took control of the pane (not by the user's choice). */
    var controlMoved by mutableStateOf(false)
        private set

    /** The remote screen is wider than this view (a watched pane keeps its controller's size). */
    var wideFrame by mutableStateOf(false)
        private set

    /** The active prompt's numbered options, read from every frame (the screen decides when to show them). */
    var promptOptions by mutableStateOf<List<PromptOption>>(emptyList())
        private set

    private var recovering = false
    private var retries = 0
    private var stableJob: Job? = null
    private var hostWait: Job? = null
    private var stream: TerminalStream? = null
    private var streamJob: Job? = null
    @Volatile private var generation = 0L
    private var resizeJob: Job? = null
    private var cols = 0
    private var rows = 0
    private var cellW = 0
    private var cellH = 0
    /** The pane's real size from the session layout, when known; watchers open at it. */
    private var paneSize: Pair<Int, Int>? = null
    private var openedSize: Pair<Int, Int>? = null
    private var observeResizeJob: Job? = null

    init {
        scope.launch {
            connection.status.collect { status ->
                when (status) {
                    // A conflated Reconnecting→Connected may never be observed as Reconnecting,
                    // leaving an interrupted stream behind: reopen that too.
                    ConnectionStatus.Connected -> if (state == PaneState.Offline ||
                        (state as? PaneState.Closed)?.interrupted == true) open(recovery = true)
                    // Whatever the pane showed, it waits for the host now; reopening on Connected
                    // re-detects a pane in use (without taking it over) or one that is gone.
                    is ConnectionStatus.Reconnecting, is ConnectionStatus.Failed -> {
                        if (state != PaneState.WaitingForLayout && state != PaneState.Offline) {
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
        refreshWideFrame()
        if (readOnly) return reopenIfObserveSizeChanged()
        // Debounce: pinch-zoom and keyboard animations produce bursts of sizes.
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(120)
            stream?.resize(this@TerminalController.cols, this@TerminalController.rows, cellW, cellH)
        }
    }

    /**
     * The pane's size in cells from the session layout (null when unknown). Watchers render at it,
     * so a pane wider than this view arrives whole instead of cropped by herdr.
     */
    fun setPaneSize(cols: Int?, rows: Int?) {
        val size = if (cols != null && rows != null && cols > 0 && rows > 0) cols to rows else null
        if (size == paneSize) return
        paneSize = size
        if (readOnly) reopenIfObserveSizeChanged()
    }

    /** A watcher can't resize its stream: reopen it at the new size once the sizes settle. */
    private fun reopenIfObserveSizeChanged() {
        observeResizeJob?.cancel()
        val opened = openedSize ?: return
        if (state != PaneState.Live || observeSize(cols, rows, paneSize) == opened) return
        observeResizeJob = scope.launch {
            delay(OPEN_DEBOUNCE_MS)
            if (readOnly && state == PaneState.Live && observeSize(cols, rows, paneSize) != openedSize) {
                start(takeover = false, observeOnly = true, recovery = false, delayMs = 0, quiet = true)
            }
        }
    }

    fun open(takeover: Boolean = false, observeOnly: Boolean = readOnly, recovery: Boolean = false) {
        retries = 0
        start(takeover, observeOnly, recovery, delayMs = 0)
    }

    /** [quiet] keeps showing the live pane while a watcher reopens at a new size. */
    private fun start(takeover: Boolean, observeOnly: Boolean, recovery: Boolean, delayMs: Long, quiet: Boolean = false) {
        readOnly = observeOnly
        if (!observeOnly) controlMoved = false
        if (cols == 0) return
        closeStream()
        observeResizeJob?.cancel()
        hostWait?.cancel()
        recovering = recovery
        if (!connection.isLive) {
            waitForHost()
            return
        }
        if (!quiet) state = PaneState.Opening
        val openingGeneration = generation
        streamJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            // Sized after the debounce, so a pane size that arrived meanwhile is used.
            val (openCols, openRows) = if (observeOnly) observeSize(cols, rows, paneSize) else cols to rows
            openedSize = openCols to openRows
            val opened = runCatching {
                connection.openTerminal(paneId, openCols, openRows, takeover, observeOnly, scope)
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
                var rendered = false
                opened.events.collect { event ->
                    when (event) {
                        is TerminalEvent.Frame -> {
                            if (!applyFrame(openingGeneration, event)) return@collect
                            // Every stream's first frame, including a watcher's quiet reopen.
                            if (!rendered) {
                                rendered = true
                                scope.launch { if (generation == openingGeneration) onLive() }
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

    private fun onLive() {
        recovering = false
        state = PaneState.Live
        // A stream that renders a frame and dies must still run out of retries: only a pane that
        // stays live earns them back. A quiet reopen counts as a new stream here too.
        stableJob?.cancel()
        stableJob = scope.launch { delay(STABLE_LIVE_MS); retries = 0 }
        // A pane size that changed while this stream was opening applies now.
        if (readOnly) reopenIfObserveSizeChanged()
    }

    private fun onClosed(event: TerminalEvent.Closed) {
        stream = null
        stableJob?.cancel()
        state = when {
            event.needsTakeover -> PaneState.Busy(event.reason.orEmpty(), afterReconnect = recovering)
            event.wasTakenOver -> {
                // Keep showing the pane: watch it while the other client has control.
                controlMoved = true
                retries = 0
                return start(takeover = false, observeOnly = true, recovery = false, delayMs = 0)
            }
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
        controlMoved = false
        if (observe != readOnly) open(takeover = false, observeOnly = observe)
    }

    /** Control the pane even if another client has it; the pane is resized to this view. */
    fun takeControl() = open(takeover = true, observeOnly = false)

    /** The pane menu's Watch only / Take control. */
    fun toggleWatching() = if (readOnly) takeControl() else setObserveOnly(true)

    private fun closeStream() {
        synchronized(model.lock) {
            generation++
            promptOptions = emptyList()
        }
        stableJob?.cancel()
        streamJob?.cancel()
        stream?.close()
        stream = null
    }

    fun dispose() {
        closeStream()
        scope.cancel()
    }

    private fun refreshWideFrame() {
        val wide = synchronized(model.lock) { isWideFrame(model.emulator.cols, cols) }
        if (wide != wideFrame) wideFrame = wide
    }

    private fun refreshPromptOptions() {
        val grid = model.emulator
        // Parsed on every frame, so the screen can show them as soon as it wants them.
        val options = parsePromptOptions((0 until grid.rows).asSequence().map(grid::rowText))
        // Only publish a change, so Compose doesn't recompose on every frame.
        if (options != promptOptions) promptOptions = options
    }

    private companion object {
        const val OPEN_DEBOUNCE_MS = 150L
        const val STABLE_LIVE_MS = 5_000L
        val RETRY_DELAYS_MS = longArrayOf(250, 1_000, 3_000)
    }

    /** Validate ownership at the same moment the frame mutates the model. */
    internal fun applyFrame(expectedGeneration: Long, frame: TerminalEvent.Frame): Boolean = synchronized(model.lock) {
        if (generation != expectedGeneration) return@synchronized false
        model.applyFrame(frame.width, frame.height, frame.full, frame.bytes)
        refreshPromptOptions()
        refreshWideFrame()
        true
    }
}

/** herdr's frame limits: up to 512 columns or rows and 65,536 cells. */
private const val MAX_FRAME_SIDE = 512
private const val MAX_FRAME_CELLS = 65_536

/**
 * The size a watcher opens at: herdr crops an observer's frames to the size it asks for, so ask
 * for the pane's real [pane] size (never smaller than this view's grid), within herdr's limits.
 * Without a known pane size the view's grid is used, as before.
 */
internal fun observeSize(viewCols: Int, viewRows: Int, pane: Pair<Int, Int>?): Pair<Int, Int> {
    val cols = max(viewCols, pane?.first ?: 0).coerceIn(1, MAX_FRAME_SIDE)
    val rows = max(viewRows, pane?.second ?: 0).coerceIn(1, minOf(MAX_FRAME_SIDE, MAX_FRAME_CELLS / cols))
    return cols to rows
}

/** Whether a [frameCols]-wide screen overflows a view [viewCols] wide (so it pans, or can fit). */
internal fun isWideFrame(frameCols: Int, viewCols: Int): Boolean = viewCols in 1 until frameCols
