package dev.herdroid.herdr

import dev.herdroid.ssh.CommandChannel
import dev.herdroid.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import dev.herdroid.ssh.closeBounded
import dev.herdroid.ssh.channelIo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

class HerdrApiException(val code: String, message: String) : Exception(message)

/** herdr isn't installed (or not where we looked) on the remote machine. */
class HerdrNotFoundException(message: String) : Exception(message)

val herdrJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * Client for herdr's newline-delimited JSON socket API.
 *
 * The socket is reached through `herdr remote-api-bridge`, the same stdio bridge herdr uses
 * for saved SSH machines. herdr serves exactly one request per socket connection, so every
 * request gets its own exec channel on the shared SSH transport; event subscriptions keep
 * theirs open.
 */
class HerdrApi internal constructor(
    private val openChannel: suspend (String) -> CommandChannel,
    private val session: String,
    private val herdrPath: String?,
) {
    constructor(ssh: SshSession, session: String, herdrPath: String?) :
        this(ssh::exec, session, herdrPath)
    private val ids = AtomicLong()
    private val concurrency = Semaphore(MAX_CONCURRENT_REQUESTS)

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): JsonObject {
        try {
            return withTimeout(timeoutMs) {
                concurrency.withPermit {
                    val channel = openChannel(RemoteCommand.apiBridge(session, herdrPath))
                    try {
                        channel.channelIo {
                            val id = "herdroid_${ids.incrementAndGet()}"
                            channel.writeLine(requestLine(id, method, params))
                            val line = channel.stdoutReader().readBoundedLine(4 * 1024 * 1024)
                                ?: throw channel.failure("herdr closed the API connection")
                            parseResponse(line)
                        }
                    } finally {
                        channel.closeBounded()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // An outer cancellation must remain cancellation. Our own deadline is
            // a transport failure so the connection loop can report it and retry.
            if (!currentCoroutineContext().isActive) throw e
            throw SocketTimeoutException("herdr $method timed out after ${timeoutMs}ms").apply { initCause(e) }
        }
    }

    /**
     * Subscribes to lifecycle events. Emits each pushed event envelope (`{"event", "data"}`).
     * Completes when the server closes the stream, and throws on `events_lost` so the caller
     * can resubscribe and reconcile from a fresh snapshot.
     */
    fun subscribe(types: List<String>): Flow<JsonObject> =
        subscribeRaw(types.map { type -> buildJsonObject { put("type", type) } })

    /** Like [subscribe] but with full subscription objects, e.g. ones scoped to a `pane_id`. */
    fun subscribeRaw(subscriptions: List<JsonObject>): Flow<JsonObject> = flow {
        var channel: CommandChannel? = null
        lateinit var input: BufferedReader
        try {
            val opened = withTimeout(REQUEST_TIMEOUT_MS) {
                val acquired = openChannel(RemoteCommand.apiBridge(session, herdrPath)).also { channel = it }
                input = acquired.stdoutReader()
                acquired.channelIo {
                    acquired.writeLine(
                        requestLine(
                            "herdroid_sub_${ids.incrementAndGet()}",
                            "events.subscribe",
                            buildJsonObject {
                                putJsonArray("subscriptions") { subscriptions.forEach { add(it) } }
                            },
                        )
                    )
                    parseResponse(input.readBoundedLine(1024 * 1024) ?: throw acquired.failure("event subscription closed"))
                }
                acquired
            }
            while (currentCoroutineContext().isActive) {
                val line = opened.channelIo { input.readBoundedLine(1024 * 1024) } ?: break
                if (line.isBlank()) continue
                val obj = herdrJson.parseToJsonElement(line).jsonObject
                if (obj.containsKey("error")) parseResponse(line)
                emit(obj)
            }
        } finally {
            channel?.closeBounded()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): SessionSnapshot {
        val result = request("session.snapshot")
        return herdrJson.decodeFromJsonElement(result.getValue("snapshot"))
    }

    suspend fun ping(): JsonObject = request("ping")

    // ---- Workspaces & tabs -------------------------------------------------------------

    suspend fun createWorkspace(label: String?, cwd: String?, focus: Boolean = false) =
        request("workspace.create", buildJsonObject {
            label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            put("focus", focus)
        })

    suspend fun renameWorkspace(workspaceId: String, label: String) =
        request("workspace.rename", buildJsonObject {
            put("workspace_id", workspaceId); put("label", label)
        })

    suspend fun closeWorkspace(workspaceId: String) =
        request("workspace.close", buildJsonObject { put("workspace_id", workspaceId) })

    suspend fun createTab(workspaceId: String, label: String?, focus: Boolean = false) =
        request("tab.create", buildJsonObject {
            put("workspace_id", workspaceId)
            label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
            put("focus", focus)
        })

    suspend fun renameTab(tabId: String, label: String) =
        request("tab.rename", buildJsonObject { put("tab_id", tabId); put("label", label) })

    suspend fun closeTab(tabId: String) =
        request("tab.close", buildJsonObject { put("tab_id", tabId) })

    // ---- Panes -------------------------------------------------------------------------

    suspend fun splitPane(paneId: String, direction: String): PaneInfo? {
        val result = request("pane.split", buildJsonObject {
            put("target_pane_id", paneId)
            put("direction", direction)
            put("focus", true)
        })
        return result["pane"]?.let { herdrJson.decodeFromJsonElement<PaneInfo>(it) }
    }

    suspend fun closePane(paneId: String) =
        request("pane.close", buildJsonObject { put("pane_id", paneId) })

    suspend fun focusPane(paneId: String) =
        request("pane.focus", buildJsonObject { put("pane_id", paneId) })

    suspend fun zoomPane(paneId: String) =
        request("pane.zoom", buildJsonObject { put("pane_id", paneId); put("mode", "toggle") })

    suspend fun renamePane(paneId: String, label: String) =
        request("pane.rename", buildJsonObject { put("pane_id", paneId); put("label", label) })

    suspend fun sendText(paneId: String, text: String) =
        request("pane.send_text", buildJsonObject { put("pane_id", paneId); put("text", text) })

    suspend fun sendKeys(paneId: String, keys: List<String>) =
        request("pane.send_keys", buildJsonObject {
            put("pane_id", paneId)
            putJsonArray("keys") { keys.forEach { add(JsonPrimitive(it)) } }
        })

    suspend fun readPane(paneId: String, source: String = "recent_unwrapped", lines: Int = 400): String {
        val result = request("pane.read", buildJsonObject {
            put("pane_id", paneId); put("source", source); put("lines", lines)
        })
        return result["read"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    // ---- Agents ------------------------------------------------------------------------

    /** Types a prompt into an agent and submits it, the way `herdr agent prompt` does. */
    suspend fun promptAgent(target: String, text: String) =
        request("agent.prompt", buildJsonObject { put("target", target); put("text", text) })

    // ---- Plumbing ----------------------------------------------------------------------

    private fun requestLine(id: String, method: String, params: JsonObject): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    private fun parseResponse(line: String): JsonObject {
        val obj = herdrJson.parseToJsonElement(line).jsonObject
        obj["error"]?.let { error ->
            val e = error.jsonObject
            throw HerdrApiException(
                code = e["code"]?.jsonPrimitive?.contentOrNull ?: "error",
                message = e["message"]?.jsonPrimitive?.contentOrNull ?: "herdr request failed",
            )
        }
        return obj["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        private const val MAX_CONCURRENT_REQUESTS = 4
        const val REQUEST_TIMEOUT_MS = 20_000L

        /** Every lifecycle event that can change what the app displays. */
        val LIFECYCLE_EVENTS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.moved",
            "workspace.reordered", "workspace.closed", "workspace.focused",
            "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
            "pane.created", "pane.closed", "pane.updated", "pane.focused", "pane.moved",
            "pane.exited", "pane.agent_detected", "layout.updated",
        )
    }
}

internal fun CommandChannel.writeLine(line: String) {
    stdin.write((line + "\n").toByteArray(Charsets.UTF_8))
    stdin.flush()
}

internal fun CommandChannel.stdoutReader(): BufferedReader =
    BufferedReader(InputStreamReader(stdout, Charsets.UTF_8), 64 * 1024)

/** Turns a dead channel into the most useful exception we can, using stderr. */
internal fun CommandChannel.failure(fallback: String): Exception {
    val err = runCatching { stderr.readAtMostBytes(16 * 1024).toString(Charsets.UTF_8).trim() }.getOrDefault("")
    return when {
        err.contains(RemoteCommand.NOT_FOUND_MESSAGE) -> HerdrNotFoundException(
            "herdr was not found on the remote machine. Install it, or set its path in the host settings."
        )
        err.contains("failed to connect to remote Herdr API socket") ||
            err.contains("No such file or directory") && err.contains("sock") -> HerdrApiException(
            "server_not_running",
            "The herdr server for this session isn't running. Start it with `herdr` or `herdr server` on the machine.",
        )
        err.contains("unrecognized") || err.contains("unknown command") || err.contains("usage:") ->
            HerdrApiException(
                "unsupported_version",
                "The remote herdr is too old for wooloo mobile. Update it with `herdr update`.\n$err",
            )
        err.isNotEmpty() -> Exception(err.lines().takeLast(4).joinToString("\n"))
        else -> Exception(fallback)
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
