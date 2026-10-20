package dev.herdroid.herdr

import java.util.Base64
import dev.herdroid.ssh.CommandChannel
import dev.herdroid.ssh.closeBounded
import dev.herdroid.ssh.channelIo
import dev.herdroid.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

sealed interface TerminalEvent {
    /** ANSI bytes that repaint (part of) a [width]x[height] screen. */
    class Frame(
        val seq: Long,
        val width: Int,
        val height: Int,
        val full: Boolean,
        val bytes: ByteArray,
    ) : TerminalEvent

    data class Closed(
        val reason: String?,
        /** The exec channel ended without an explicit terminal.closed record. Retry after SSH recovery. */
        val interrupted: Boolean = false,
    ) : TerminalEvent {
        /** Another client owns this terminal; reconnecting with takeover would steal it. */
        val needsTakeover: Boolean get() = reason?.contains("--takeover") == true
        val wasTakenOver: Boolean get() = reason?.contains("taken over") == true
    }
}

enum class MouseAction { DOWN, UP, DRAG, MOVE }

/**
 * A live pane stream: `herdr terminal session control|observe` running over an SSH exec
 * channel. herdr re-renders the pane server-side and sends compact ANSI frames as NDJSON;
 * we send NDJSON commands (input, resize, scroll, mouse, release) on stdin.
 */
class TerminalStream private constructor(
    private val channel: CommandChannel,
    private val scope: CoroutineScope,
    val readOnly: Boolean,
) {
    // Single consumer; buffered so the first full frame is never lost before collection starts.
    private val _events = Channel<TerminalEvent>(capacity = 8)
    @Volatile private var closedReason: String? = null
    @Volatile private var explicitClose = false
    val events: Flow<TerminalEvent> = flow {
        for (event in _events) emit(event)
        emit(TerminalEvent.Closed(closedReason, interrupted = !explicitClose))
    }

    private val outbox = Channel<String>(capacity = 64)
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    @Volatile
    var isClosed = false
        private set

    private fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = channel.stdoutReader()
                while (true) {
                    val line = channel.channelIo { reader.readBoundedLine(MAX_RECORD_CHARS) } ?: break
                    if (line.isBlank()) continue
                    val event = parse(line) ?: continue
                    if (event is TerminalEvent.Closed) {
                        explicitClose = true
                        closedReason = event.reason
                        break
                    }
                    _events.send(event)
                }
                if (closedReason == null) {
                    closedReason = runCatching {
                        withTimeoutOrNull(1000) {
                            channel.channelIo { channel.stderr.readAtMostBytes(16 * 1024).toString(Charsets.UTF_8).trim() }
                        }
                    }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            } catch (t: Throwable) {
                closedReason = closedReason ?: t.message
            } finally {
                outbox.close()
                channel.closeBounded(terminateRemote = readOnly && !currentCoroutineContext().isActive)
                isClosed = true
                _events.close()
            }
        }
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (line in outbox) channel.channelIo { channel.writeLine(line) }
            } catch (e: Throwable) {
                closedReason = closedReason ?: "Terminal write failed"
                channel.closeBounded(terminateRemote = readOnly)
            }
        }
    }

    private fun parse(line: String): TerminalEvent? {
        val obj = runCatching { herdrJson.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return null
        return when (obj.string("type")) {
            "terminal.frame" -> {
                val width = obj["width"]?.jsonPrimitive?.int ?: 0
                val height = obj["height"]?.jsonPrimitive?.int ?: 0
                if (width !in 1..512 || height !in 1..512 || width.toLong() * height > 65_536) {
                    throw IOException("Invalid terminal frame dimensions")
                }
                val encoded = obj.string("bytes").orEmpty()
                if (encoded.length > 1_398_104) throw IOException("Terminal frame is too large")
                val bytes = Base64.getDecoder().decode(encoded)
                if (bytes.size > MAX_FRAME_BYTES) throw IOException("Terminal frame is too large")
                TerminalEvent.Frame(
                seq = obj["seq"]?.jsonPrimitive?.long ?: 0,
                width = width,
                height = height,
                full = obj["full"]?.jsonPrimitive?.boolean ?: false,
                bytes = bytes,
            )
            }
            "terminal.closed" -> TerminalEvent.Closed(obj["reason"]?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }

    private fun send(command: JsonObject): Boolean {
        if (!readOnly && !isClosed) {
            val record = command.toString()
            if (record.length > 65_536 || outbox.trySend(record).isFailure) {
                closedReason = "Terminal input buffer is full; reconnect before sending more input"
                isClosed = true
                readerJob?.cancel()
                writerJob?.cancel()
                _events.close()
                outbox.close()
                close()
                return false
            }
            return true
        }
        return false
    }

    fun input(text: String) {
        tryInput(text)
    }

    fun tryInput(text: String): Boolean = text.isEmpty() ||
        send(buildJsonObject { put("type", "terminal.input"); put("text", text) })

    fun inputBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        send(buildJsonObject {
            put("type", "terminal.input")
            put("bytes", Base64.getEncoder().encodeToString(bytes))
        })
    }

    fun resize(cols: Int, rows: Int, cellWidthPx: Int = 0, cellHeightPx: Int = 0) {
        if (cols <= 0 || rows <= 0) return
        send(buildJsonObject {
            put("type", "terminal.resize")
            put("cols", cols)
            put("rows", rows)
            if (cellWidthPx > 0) put("cell_width_px", cellWidthPx)
            if (cellHeightPx > 0) put("cell_height_px", cellHeightPx)
        })
    }

    fun scroll(up: Boolean, lines: Int, column: Int? = null, row: Int? = null) {
        if (lines <= 0) return
        send(buildJsonObject {
            put("type", "terminal.scroll")
            put("direction", if (up) "up" else "down")
            put("lines", lines.coerceAtMost(500))
            column?.let { put("column", it) }
            row?.let { put("row", it) }
        })
    }

    fun mouse(action: MouseAction, column: Int, row: Int, button: String = "left") {
        send(buildJsonObject {
            put("type", "terminal.mouse")
            put("action", action.name.lowercase())
            put("button", button)
            put("column", column)
            put("row", row)
        })
    }

    fun click(column: Int, row: Int) {
        mouse(MouseAction.DOWN, column, row)
        mouse(MouseAction.UP, column, row)
    }

    /** Hands control back and closes the stream. */
    fun close() {
        if (!isClosed && !readOnly) {
            outbox.trySend(buildJsonObject { put("type", "terminal.release") }.toString())
        }
        outbox.close()
        scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { withTimeoutOrNull(500) { writerJob?.join() } } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    channel.closeBounded(terminateRemote = readOnly)
                    readerJob?.cancel()
                    writerJob?.cancel()
                }
            }
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 1_048_576
        private const val MAX_RECORD_CHARS = 1_500_000

        internal fun fromChannel(channel: CommandChannel, scope: CoroutineScope, readOnly: Boolean = false) =
            TerminalStream(channel, scope, readOnly).also { it.start() }
        suspend fun open(
            ssh: SshSession,
            scope: CoroutineScope,
            session: String,
            herdrPath: String?,
            target: String,
            cols: Int,
            rows: Int,
            takeover: Boolean,
            readOnly: Boolean,
        ): TerminalStream {
            val command = if (readOnly) {
                RemoteCommand.terminalObserve(session, herdrPath, target, cols, rows)
            } else {
                RemoteCommand.terminalControl(session, herdrPath, target, cols, rows, takeover)
            }
            val channel = ssh.exec(command)
            return TerminalStream(channel, scope, readOnly).also { it.start() }
        }
    }
}
