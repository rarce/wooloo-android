package dev.herdroid.herdr

import java.util.Base64
import dev.herdroid.ssh.ExecChannel
import dev.herdroid.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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

    data class Closed(val reason: String?) : TerminalEvent {
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
    private val channel: ExecChannel,
    private val scope: CoroutineScope,
    val readOnly: Boolean,
) {
    // Single consumer; buffered so the first full frame is never lost before collection starts.
    private val _events = Channel<TerminalEvent>(capacity = Channel.UNLIMITED)
    val events: Flow<TerminalEvent> = _events.receiveAsFlow()

    private val outbox = Channel<String>(capacity = Channel.UNLIMITED)
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    @Volatile
    var isClosed = false
        private set

    private fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            var closedReason: String? = null
            try {
                val reader = channel.stdoutReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val event = parse(line) ?: continue
                    if (event is TerminalEvent.Closed) {
                        closedReason = event.reason
                        break
                    }
                    _events.send(event)
                }
                if (closedReason == null) {
                    closedReason = runCatching {
                        channel.stderr.readBytes().toString(Charsets.UTF_8).trim()
                    }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            } catch (t: Throwable) {
                closedReason = closedReason ?: t.message
            } finally {
                isClosed = true
                _events.trySend(TerminalEvent.Closed(closedReason))
                _events.close()
                outbox.close()
                channel.close()
            }
        }
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (line in outbox) channel.writeLine(line)
            } catch (_: Throwable) {
                // The reader notices the dead channel and reports it.
            }
        }
    }

    private fun parse(line: String): TerminalEvent? {
        val obj = runCatching { herdrJson.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return null
        return when (obj.string("type")) {
            "terminal.frame" -> TerminalEvent.Frame(
                seq = obj["seq"]?.jsonPrimitive?.long ?: 0,
                width = obj["width"]?.jsonPrimitive?.int ?: 0,
                height = obj["height"]?.jsonPrimitive?.int ?: 0,
                full = obj["full"]?.jsonPrimitive?.boolean ?: false,
                bytes = Base64.getDecoder().decode(obj.string("bytes").orEmpty()),
            )
            "terminal.closed" -> TerminalEvent.Closed(obj["reason"]?.jsonPrimitive?.contentOrNull)
            else -> null
        }
    }

    private fun send(command: JsonObject) {
        if (!readOnly && !isClosed) outbox.trySend(command.toString())
    }

    fun input(text: String) {
        if (text.isEmpty()) return
        send(buildJsonObject { put("type", "terminal.input"); put("text", text) })
    }

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
        scope.launch(Dispatchers.IO) {
            writerJob?.join()
            channel.close()
            readerJob?.cancel()
        }
    }

    companion object {
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
