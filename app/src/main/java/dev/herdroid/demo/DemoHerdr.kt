package dev.herdroid.demo

import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.PaneLayoutPane
import dev.herdroid.herdr.PaneLayoutRect
import dev.herdroid.herdr.PaneLayoutSnapshot
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.WorkspaceInfo
import dev.herdroid.herdr.herdrJson
import dev.herdroid.herdr.string
import dev.herdroid.ssh.CommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * A small herdr server simulated in memory: a few spaces with agents that work, finish and
 * ask for permission, shells, and the API and terminal streams the app uses. It answers the
 * command lines from `RemoteCommand`, so the rest of the app runs exactly as against a real
 * machine. With [ambient], agents and logs move on their own on a short timeline.
 */
internal class DemoHerdr(ambient: Boolean = true) {
    private class Space(val id: String, var label: String, val cwd: String) {
        var activeTab = ""
    }

    private class Tab(val id: String, val spaceId: String, var label: String) {
        val panes = ArrayList<String>()
        var focusedPane = ""
        var zoomed = false
    }

    private class Permission(
        val tool: String,
        val command: String,
        val workingTitle: String,
        val approved: List<Line>,
        val summary: List<Line>,
        val doneTitle: String,
    )

    private class Agent(val kind: String, val display: String, val glyph: Span, var name: String?) {
        var status = AgentStatus.IDLE
        var title: String? = null
        var seq = 0L
        var permission: Permission? = null
        /** What the agent is doing on its own; user input cancels it. */
        var job: Job? = null
    }

    private class Pane(val id: String, val terminalId: String, val spaceId: String, val tabId: String, var cwd: String) {
        var label: String? = null
        var agent: Agent? = null
        val transcript = ArrayList<Line>()
        val input = StringBuilder()
        /** A shell's foreground command that keeps printing until Ctrl-C. */
        var process: String? = null
        var lastCommand: String? = null
    }

    private class Session(val pane: Pane, val channel: DemoChannel, var cols: Int, var rows: Int, val readOnly: Boolean) {
        var offset = 0
        var seq = 0L
    }

    class DemoError(val code: String, message: String) : Exception(message)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val spaces = ArrayList<Space>()
    private val tabs = ArrayList<Tab>()
    private val panes = LinkedHashMap<String, Pane>()
    private val subscribers = LinkedHashSet<DemoChannel>()
    private val sessions = ArrayList<Session>()
    private val dirty = LinkedHashSet<Pane>()
    private var structureChanged = false
    private var focusedPane: String? = null
    private var ids = 10
    private var stateSeq = 10L
    private var cpuBusy = 2_400_000L
    private var cpuTotal = 19_000_000L
    private val startedAt = System.currentTimeMillis()

    init {
        seed()
        if (ambient) startAmbient()
    }

    internal val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun stop() {
        synchronized(lock) {
            // Like a dropped SSH transport: streams end without herdr saying why.
            subscribers.forEach { it.end() }
            subscribers.clear()
            sessions.forEach { it.channel.end() }
            sessions.clear()
        }
        scope.cancel()
    }

    // ---- Commands --------------------------------------------------------------------------

    fun exec(command: String): CommandChannel {
        val channel = DemoChannel()
        val terminal = TERMINAL.find(command)
        when {
            "remote-api-bridge" in command -> channel.onInput = { request(channel, it) }
            terminal != null -> {
                val (mode, target, cols, rows) = terminal.destructured
                openTerminal(channel, target, cols.toInt(), rows.toInt(), readOnly = mode == "observe")
            }
            "g/%s" in command -> channel.finishWith(listDirectories(lastArgument(command)))
            "herdroid-" in command && "cat >" in command -> {
                val name = lastArgument(command)
                channel.onInputEnd = { channel.write("/tmp/herdroid-1000/$name".toByteArray()); channel.end() }
            }
            "echo \"host=" in command -> channel.finishWith(hostProbe())
            "printf %s" in command && "HOME" in command -> channel.finishWith(HOME)
            // The agent quota probe, and anything else: nothing is signed in on the demo machine.
            else -> channel.end()
        }
        return channel
    }

    private fun DemoChannel.finishWith(output: String) {
        write(output.toByteArray(Charsets.UTF_8))
        end()
    }

    private fun request(channel: DemoChannel, line: String) {
        if (line.isBlank()) return
        val request = runCatching { herdrJson.parseToJsonElement(line).jsonObject }.getOrNull()
        val id = request?.string("id") ?: "unknown"
        val method = request?.string("method").orEmpty()
        val params = request?.get("params") as? JsonObject ?: JsonObject(emptyMap())
        if (method == "events.subscribe") {
            synchronized(lock) { subscribers += channel }
            channel.onClose = { synchronized(lock) { subscribers -= channel } }
            channel.send(response(id, JsonObject(emptyMap())))
            return
        }
        val reply = try {
            response(id, synchronized(lock) { call(method, params).also { flush() } })
        } catch (e: DemoError) {
            buildJsonObject {
                put("id", id)
                put("error", buildJsonObject { put("code", e.code); put("message", e.message.orEmpty()) })
            }
        }
        channel.send(reply.toString())
        channel.end()
    }

    private fun response(id: String, result: JsonObject) =
        buildJsonObject { put("id", id); put("result", result) }.toString()

    private fun call(method: String, params: JsonObject): JsonObject {
        val empty = JsonObject(emptyMap())
        when (method) {
            "ping" -> return empty
            "session.snapshot" -> return buildJsonObject { put("snapshot", herdrJson.encodeToJsonElement(snapshot())) }
            "workspace.create" -> createSpace(params.string("label"), params.string("cwd"), params.flag("focus"))
            "workspace.rename" -> { space(params).label = params.required("label"); structureChanged = true }
            "workspace.close" -> closeSpace(space(params))
            "tab.create" -> createTab(space(params), params.string("label"), params.flag("focus"))
            "tab.rename" -> { tab(params).label = params.required("label"); structureChanged = true }
            "tab.close" -> closeTab(tab(params))
            "pane.split" -> {
                val pane = split(pane(params, "target_pane_id"))
                return buildJsonObject { put("pane", herdrJson.encodeToJsonElement(paneInfo(pane))) }
            }
            "pane.close" -> closePane(pane(params))
            "pane.focus" -> focus(pane(params))
            "pane.zoom" -> {
                val pane = pane(params)
                tabs.first { it.id == pane.tabId }.apply { zoomed = !zoomed; focusedPane = pane.id }
                structureChanged = true
            }
            "pane.rename" -> { pane(params).label = params.required("label").ifBlank { null }; structureChanged = true }
            "pane.send_text" -> input(pane(params), params.required("text"))
            "pane.send_keys" -> {
                val pane = pane(params)
                (params["keys"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .forEach { key(pane, KEYS[it] ?: it) }
            }
            "pane.read" -> {
                val pane = pane(params)
                val lines = (params["lines"] as? JsonPrimitive)?.intOrNull ?: 400
                val text = pane.transcript.takeLast(lines).joinToString("\n") { row -> row.joinToString("") { it.text } }
                return buildJsonObject { put("read", buildJsonObject { put("text", text) }) }
            }
            "agent.prompt" -> {
                val pane = panes[params.required("target")] ?: throw DemoError("not_found", "No such agent")
                val agent = pane.agent ?: throw DemoError("not_an_agent", "That pane has no agent")
                agent.permission?.let { reject(pane, agent, it) }
                submit(pane, agent, params.required("text"))
            }
            else -> throw DemoError("unknown_method", "The demo machine doesn't support $method")
        }
        return empty
    }

    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.required(key: String) = string(key) ?: throw DemoError("invalid_params", "Missing $key")
    private fun space(params: JsonObject) = params.required("workspace_id").let { id ->
        spaces.firstOrNull { it.id == id } ?: throw DemoError("not_found", "No such space")
    }
    private fun tab(params: JsonObject) = params.required("tab_id").let { id ->
        tabs.firstOrNull { it.id == id } ?: throw DemoError("not_found", "No such tab")
    }
    private fun pane(params: JsonObject, key: String = "pane_id") =
        panes[params.required(key)] ?: throw DemoError("not_found", "No such pane")

    // ---- Terminals -------------------------------------------------------------------------

    private fun openTerminal(channel: DemoChannel, paneId: String, cols: Int, rows: Int, readOnly: Boolean) = synchronized(lock) {
        val pane = panes[paneId]
        if (pane == null) {
            channel.send(closedRecord("pane $paneId not found"))
            channel.end()
            return@synchronized
        }
        val session = Session(pane, channel, 0, 0, readOnly).also { it.resize(cols, rows) }
        sessions += session
        channel.onClose = { synchronized(lock) { sessions.remove(session) } }
        channel.onInput = { terminalRecord(session, it) }
        sendFrame(session)
    }

    private fun Session.resize(cols: Int, rows: Int) {
        this.cols = cols.coerceIn(1, 512)
        this.rows = rows.coerceIn(1, minOf(512, 65_536 / this.cols))
    }

    private fun terminalRecord(session: Session, line: String) {
        val record = runCatching { herdrJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        synchronized(lock) {
            if (session !in sessions) return
            when (record.string("type")) {
                "terminal.input" -> if (!session.readOnly) {
                    val text = record.string("text")
                        ?: record.string("bytes")?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
                        ?: return
                    session.offset = 0
                    input(session.pane, text)
                }
                "terminal.resize" -> {
                    session.resize(
                        (record["cols"] as? JsonPrimitive)?.intOrNull ?: session.cols,
                        (record["rows"] as? JsonPrimitive)?.intOrNull ?: session.rows,
                    )
                    sendFrame(session)
                }
                "terminal.scroll" -> {
                    val lines = (record["lines"] as? JsonPrimitive)?.intOrNull ?: 0
                    session.offset = (session.offset + if (record.string("direction") == "up") lines else -lines).coerceAtLeast(0)
                    sendFrame(session)
                }
                "terminal.release" -> {
                    sessions.remove(session)
                    session.channel.end()
                }
            }
            flush()
        }
    }

    private fun sendFrame(session: Session) {
        val (screen, offset) = render(session.pane, session.cols, session.rows, session.offset)
        session.offset = offset
        val record = buildJsonObject {
            put("type", "terminal.frame")
            put("seq", ++session.seq)
            put("width", session.cols)
            put("height", session.rows)
            put("full", true)
            put("bytes", Base64.getEncoder().encodeToString(screen.ansi()))
        }
        session.channel.send(record.toString())
    }

    private fun closedRecord(reason: String) =
        buildJsonObject { put("type", "terminal.closed"); put("reason", reason) }.toString()

    /** Sends what changed: new frames for touched panes, and one invalidation event for the snapshot. */
    private fun flush() {
        dirty.forEach { pane ->
            if (pane.transcript.size > MAX_TRANSCRIPT) pane.transcript.subList(0, pane.transcript.size - MAX_TRANSCRIPT).clear()
        }
        sessions.filter { it.pane in dirty }.forEach(::sendFrame)
        dirty.clear()
        if (structureChanged) {
            structureChanged = false
            subscribers.forEach { it.send("""{"event":"pane.updated","data":{}}""") }
        }
    }

    private fun touch(pane: Pane) {
        dirty += pane
    }

    private fun render(pane: Pane, cols: Int, rows: Int, offset: Int): Pair<DemoScreen, Int> {
        val agent = pane.agent
        val footer = ArrayList<Line>()
        // The footer line holding the input, and the cursor column on it.
        var cursor: Pair<Int, Int>? = null
        val body = ArrayList<Line>()
        if (agent != null) {
            body += line(Span(abbreviate(pane.cwd), Style.DIM), Span("  "), agent.glyph)
            body += emptyList<Span>()
            body += pane.transcript
            val rule = text("─".repeat(cols), Style.DIM)
            val permission = agent.permission
            if (permission != null) {
                footer += rule
                footer += text(" ${permission.tool}", Style.BOLD)
                footer += text("   ${permission.command}")
                footer += text(" Do you want to proceed?")
                footer += text(" ❯ 1. Yes", Style.BLUE)
                footer += text("   2. Yes, and don't ask again")
                footer += text("   3. No, and tell ${agent.display} what to do")
                footer += rule
            } else {
                footer += emptyList<Span>()
                if (agent.status == AgentStatus.WORKING) {
                    footer += line(Span("✻ Working… ", Style.ORANGE), Span("(esc to interrupt)", Style.DIM))
                }
                footer += rule
                val typed = pane.input.toString().takeLast((cols - 3).coerceAtLeast(0))
                cursor = footer.size to minOf(cols - 1, 2 + typed.length)
                footer += line(Span("> ", Style.DIM), Span(typed))
                footer += rule
            }
        } else {
            body += pane.transcript
            if (pane.process == null) body += prompt(pane) + Span(pane.input.toString())
        }
        val wrappedFooter = footer.map { DemoScreen.wrap(it, cols) }
        val allFooterRows = wrappedFooter.flatten()
        val footerRows = allFooterRows.takeLast(rows)
        val cursorRow = cursor?.let { (index, _) ->
            wrappedFooter.take(index).sumOf { it.size } - (allFooterRows.size - footerRows.size)
        }
        val bodyRows = body.flatMap { DemoScreen.wrap(it, cols) }
        val room = rows - footerRows.size
        val clamped = offset.coerceIn(0, (bodyRows.size - room).coerceAtLeast(0))
        val end = bodyRows.size - clamped
        val visible = if (room > 0) bodyRows.subList((end - room).coerceAtLeast(0), end) else emptyList()
        val screen = when {
            cursor != null && cursorRow != null && cursorRow >= 0 ->
                DemoScreen(visible + footerRows, visible.size + cursorRow, cursor.second)
            agent == null && pane.process == null && clamped == 0 && visible.isNotEmpty() ->
                DemoScreen(visible, visible.size - 1, minOf(cols - 1, DemoScreen.width(visible.last())))
            else -> DemoScreen(visible + footerRows, minOf(rows - 1, visible.size + footerRows.size), 0, cursorVisible = false)
        }
        return screen to clamped
    }

    // ---- Keys ------------------------------------------------------------------------------

    private fun input(pane: Pane, text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u001b' && i + 1 < text.length && (text[i + 1] == '[' || text[i + 1] == 'O')) {
                var j = i + 2
                while (j < text.length && text[j] !in '@'..'~') j++
                key(pane, text.substring(i, minOf(j + 1, text.length)))
                i = j + 1
            } else {
                key(pane, c.toString())
                i++
            }
        }
    }

    private fun key(pane: Pane, key: String) {
        val agent = pane.agent
        if (agent != null) agentKey(pane, agent, key) else shellKey(pane, key)
        touch(pane)
    }

    private fun agentKey(pane: Pane, agent: Agent, key: String) {
        agent.permission?.let { permission ->
            when (key) {
                "1", "2", "y", "Y", "\r", "\n" -> approve(pane, agent, permission)
                "3", "n", "N", "\u001b" -> reject(pane, agent, permission)
            }
            return
        }
        when (key) {
            "\r", "\n" -> {
                val prompt = pane.input.toString().trim()
                pane.input.clear()
                if (prompt.isNotEmpty()) submit(pane, agent, prompt)
            }
            "\u007f", "\b" -> if (pane.input.isNotEmpty()) pane.input.setLength(pane.input.length - 1)
            "\u0003", "\u001b" -> if (agent.status == AgentStatus.WORKING) interrupt(pane, agent) else pane.input.clear()
            else -> typed(pane, key)
        }
    }

    private fun shellKey(pane: Pane, key: String) {
        if (pane.process != null) {
            if (key == "\u0003") {
                pane.process = null
                pane.transcript += text("^C")
                structureChanged = true
            }
            return
        }
        when (key) {
            "\r", "\n" -> {
                val command = pane.input.toString()
                pane.input.clear()
                pane.transcript += prompt(pane) + Span(command)
                run(pane, command.trim())
            }
            "\u007f", "\b" -> if (pane.input.isNotEmpty()) pane.input.setLength(pane.input.length - 1)
            "\u0003" -> {
                pane.transcript += prompt(pane) + Span(pane.input.toString()) + Span("^C")
                pane.input.clear()
            }
            "\u000c" -> pane.transcript.clear()
            "\u001b[A", "\u001bOA" -> pane.lastCommand?.let { pane.input.setLength(0); pane.input.append(it) }
            else -> typed(pane, key)
        }
    }

    private fun typed(pane: Pane, key: String) {
        if (key.length == 1 && (key[0] >= ' ' && key[0] != '\u007f' || Character.isSurrogate(key[0])) && pane.input.length < 2_000) {
            pane.input.append(key)
        }
    }

    // ---- Agents ----------------------------------------------------------------------------

    private fun setStatus(agent: Agent, status: AgentStatus, title: String?) {
        if (agent.status != status) {
            agent.status = status
            agent.seq = ++stateSeq
        }
        agent.title = title
        structureChanged = true
    }

    private fun submit(pane: Pane, agent: Agent, prompt: String) {
        agent.job?.cancel()
        pane.transcript += emptyList<Span>()
        pane.transcript += line(Span("> ", Style.BOLD), Span(prompt))
        setStatus(agent, AgentStatus.WORKING, prompt.take(60))
        touch(pane)
        agent.job = later(pane, 2_500) {
            pane.transcript += emptyList<Span>()
            pane.transcript += line(
                Span("⏺ ", Style.GREEN),
                Span("This is the demo machine, so nothing ran. On your own machine, ${agent.display} " +
                    "would work on this now and you would follow it from here."),
            )
            setStatus(agent, AgentStatus.DONE, "Answered: ${prompt.take(40)}")
        }
    }

    private fun approve(pane: Pane, agent: Agent, permission: Permission) {
        agent.permission = null
        pane.transcript += emptyList<Span>()
        pane.transcript += permission.approved
        setStatus(agent, AgentStatus.WORKING, permission.workingTitle)
        agent.job = later(pane, 3_000) {
            pane.transcript += emptyList<Span>()
            pane.transcript += permission.summary
            setStatus(agent, AgentStatus.DONE, permission.doneTitle)
        }
    }

    private fun reject(pane: Pane, agent: Agent, permission: Permission) {
        agent.permission = null
        pane.transcript += line(Span("  ⎿  ", Style.DIM), Span("Didn't run ${permission.command}", Style.RED))
        setStatus(agent, AgentStatus.IDLE, "Waiting for instructions")
    }

    private fun interrupt(pane: Pane, agent: Agent) {
        agent.job?.cancel()
        agent.job = null
        pane.transcript += line(Span("  ⎿  ", Style.DIM), Span("Interrupted by user", Style.RED))
        setStatus(agent, AgentStatus.IDLE, "Interrupted")
    }

    /** Runs [block] after [delayMs] unless [pane] was closed meanwhile. */
    private fun later(pane: Pane, delayMs: Long, block: () -> Unit): Job = scope.launch {
        delay(delayMs)
        synchronized(lock) {
            if (panes[pane.id] !== pane) return@launch
            block()
            touch(pane)
            flush()
        }
    }

    // ---- Shells ----------------------------------------------------------------------------

    private fun prompt(pane: Pane): Line =
        line(Span("demo@herdr", Style.GREEN), Span(":"), Span(abbreviate(pane.cwd), Style.BLUE), Span("$ "))

    private fun run(pane: Pane, command: String) {
        if (command.isEmpty()) return
        pane.lastCommand = command
        val args = command.split(Regex("\\s+"))
        val out = pane.transcript
        when (args[0]) {
            "help" -> out += text("Demo shell. Try ls, cd, pwd, git status, echo, date or clear.")
            "ls" -> {
                val target = args.drop(1).firstOrNull { !it.startsWith("-") }
                val dir = resolve(pane.cwd, target)
                val entries = TREE[dir]
                if (entries == null) out += text("ls: cannot access '$target': No such file or directory")
                else if (entries.isNotEmpty()) out += entries.flatMapIndexed { index, entry ->
                    listOfNotNull(
                        Span("  ").takeIf { index > 0 },
                        if (entry.dir) Span(entry.name, Style.BLUE) else Span(entry.name),
                    )
                }
            }
            "cd" -> {
                val dir = resolve(pane.cwd, args.getOrNull(1) ?: "~")
                if (dir in TREE) {
                    pane.cwd = dir
                    structureChanged = true
                } else {
                    out += text("cd: no such file or directory: ${args.getOrNull(1)}")
                }
            }
            "pwd" -> out += text(pane.cwd)
            "echo" -> out += text(command.removePrefix("echo").trim())
            "clear" -> out.clear()
            "date" -> out += text(Date().toString())
            "whoami" -> out += text("demo")
            "git" -> if (args.getOrNull(1) == "status") {
                out += text("On branch main")
                out += text("Your branch is up to date with 'origin/main'.")
                out += emptyList<Span>()
                out += text("nothing to commit, working tree clean")
            } else {
                out += text("git: only `git status` works on the demo machine")
            }
            "herdr" -> out += text("herdr 0.9.3 (simulated by wooloo mobile's demo)")
            else -> out += text("${args[0]}: not available on the demo machine. Try help.")
        }
    }

    // ---- Structure -------------------------------------------------------------------------

    private fun createSpace(label: String?, cwd: String?, focus: Boolean): Space {
        val dir = cwd?.trim()?.takeIf { it.isNotEmpty() }?.let { resolve(HOME, it) } ?: HOME
        val space = Space("w${++ids}", label?.takeIf { it.isNotBlank() } ?: dir.substringAfterLast('/').ifEmpty { "space" }, dir)
        spaces += space
        createTab(space, null, focus)
        return space
    }

    private fun createTab(space: Space, label: String?, focus: Boolean): Tab {
        val tab = Tab("${space.id}:t${++ids}", space.id, label?.takeIf { it.isNotBlank() }.orEmpty())
        tabs += tab
        if (space.activeTab.isEmpty()) space.activeTab = tab.id
        val pane = newPane(tab, space.cwd)
        if (focus) focus(pane)
        structureChanged = true
        return tab
    }

    private fun newPane(tab: Tab, cwd: String, after: String? = null): Pane {
        val pane = Pane("${tab.spaceId}:p${++ids}", "t${++ids}", tab.spaceId, tab.id, cwd)
        panes[pane.id] = pane
        val index = after?.let { tab.panes.indexOf(it) }?.takeIf { it >= 0 }
        if (index != null) tab.panes.add(index + 1, pane.id) else tab.panes += pane.id
        if (tab.focusedPane.isEmpty()) tab.focusedPane = pane.id
        structureChanged = true
        return pane
    }

    private fun split(target: Pane): Pane {
        val tab = tabs.first { it.id == target.tabId }
        val pane = newPane(tab, target.cwd, after = target.id)
        focus(pane)
        return pane
    }

    private fun focus(pane: Pane) {
        focusedPane = pane.id
        tabs.first { it.id == pane.tabId }.focusedPane = pane.id
        spaces.first { it.id == pane.spaceId }.activeTab = pane.tabId
        structureChanged = true
    }

    private fun removePane(pane: Pane) {
        panes.remove(pane.id)
        pane.agent?.job?.cancel()
        tabs.firstOrNull { it.id == pane.tabId }?.let { tab ->
            tab.panes.remove(pane.id)
            if (tab.focusedPane == pane.id) tab.focusedPane = tab.panes.firstOrNull().orEmpty()
        }
        sessions.filter { it.pane === pane }.forEach {
            it.channel.send(closedRecord("pane closed"))
            it.channel.end()
            sessions.remove(it)
        }
        dirty.remove(pane)
        structureChanged = true
    }

    private fun closePane(pane: Pane) {
        removePane(pane)
        val tab = tabs.firstOrNull { it.id == pane.tabId }
        if (tab != null && tab.panes.isEmpty()) closeTab(tab)
        repairFocus()
    }

    private fun closeTab(tab: Tab) {
        tab.panes.toList().mapNotNull { panes[it] }.forEach(::removePane)
        tabs.remove(tab)
        val space = spaces.firstOrNull { it.id == tab.spaceId } ?: return
        if (tabs.none { it.spaceId == space.id }) closeSpace(space)
        else if (space.activeTab == tab.id) space.activeTab = tabs.first { it.spaceId == space.id }.id
        repairFocus()
    }

    private fun closeSpace(space: Space) {
        tabs.filter { it.spaceId == space.id }.forEach { tab ->
            tab.panes.toList().mapNotNull { panes[it] }.forEach(::removePane)
            tabs.remove(tab)
        }
        spaces.remove(space)
        structureChanged = true
        repairFocus()
    }

    private fun repairFocus() {
        if (focusedPane !in panes) focusedPane = panes.keys.firstOrNull()
    }

    // ---- Snapshot --------------------------------------------------------------------------

    private fun snapshot(): SessionSnapshot {
        val focusedSpace = focusedPane?.let { panes[it] }?.spaceId
        val ordered = spaces.flatMap { space -> tabs.filter { it.spaceId == space.id }.flatMap { tab -> tab.panes.mapNotNull { panes[it] } } }
        return SessionSnapshot(
            version = "0.9.3",
            protocol = 22,
            focusedWorkspaceId = focusedSpace,
            focusedTabId = focusedPane?.let { panes[it] }?.tabId,
            focusedPaneId = focusedPane,
            workspaces = spaces.mapIndexed { index, space ->
                val spaceTabs = tabs.filter { it.spaceId == space.id }
                WorkspaceInfo(
                    workspaceId = space.id, number = index + 1, label = space.label,
                    focused = space.id == focusedSpace,
                    paneCount = spaceTabs.sumOf { it.panes.size }, tabCount = spaceTabs.size,
                    activeTabId = space.activeTab,
                    agentStatus = status(ordered.filter { it.spaceId == space.id }),
                )
            },
            tabs = spaces.flatMap { space ->
                tabs.filter { it.spaceId == space.id }.mapIndexed { index, tab ->
                    TabInfo(
                        tabId = tab.id, workspaceId = space.id, number = index + 1,
                        label = tab.label.ifBlank { "${index + 1}" }, focused = tab.id == space.activeTab,
                        paneCount = tab.panes.size, agentStatus = status(tab.panes.mapNotNull { panes[it] }),
                    )
                }
            },
            panes = ordered.map(::paneInfo),
            layouts = tabs.map(::layout),
            agents = ordered.mapNotNull { pane ->
                val agent = pane.agent ?: return@mapNotNull null
                AgentInfo(
                    paneId = pane.id, terminalId = pane.terminalId, workspaceId = pane.spaceId, tabId = pane.tabId,
                    agentStatus = agent.status, agent = agent.kind, displayAgent = agent.display, name = agent.name,
                    title = agent.title, cwd = pane.cwd, stateChangeSeq = agent.seq,
                    focused = pane.id == focusedPane, revision = 1,
                )
            },
        )
    }

    private fun paneInfo(pane: Pane) = PaneInfo(
        paneId = pane.id, terminalId = pane.terminalId, workspaceId = pane.spaceId, tabId = pane.tabId,
        focused = pane.id == focusedPane, agentStatus = pane.agent?.status ?: AgentStatus.UNKNOWN,
        agent = pane.agent?.kind, displayAgent = pane.agent?.display, label = pane.label, cwd = pane.cwd,
        terminalTitleStripped = pane.agent?.title ?: pane.process, revision = 1,
    )

    private fun layout(tab: Tab): PaneLayoutSnapshot {
        val ids = tab.panes
        val rects = ids.mapIndexed { index, id ->
            val rect = when {
                ids.size == 1 -> PaneLayoutRect(0, 0, 160, 48)
                index == 0 -> PaneLayoutRect(0, 0, 80, 48)
                else -> {
                    val height = 48 / (ids.size - 1)
                    PaneLayoutRect(80, (index - 1) * height, 80, height)
                }
            }
            PaneLayoutPane(id, focused = id == tab.focusedPane, rect = rect)
        }
        return PaneLayoutSnapshot(tab.spaceId, tab.id, tab.zoomed, PaneLayoutRect(0, 0, 160, 48), tab.focusedPane, rects)
    }

    private fun status(panes: List<Pane>): AgentStatus =
        panes.mapNotNull { it.agent?.status }.maxByOrNull { it.attention } ?: AgentStatus.UNKNOWN

    // ---- Host ------------------------------------------------------------------------------

    private fun hostProbe(): String = synchronized(lock) {
        cpuTotal += 800
        cpuBusy += Random.nextLong(110, 330)
        val uptime = 3 * 86_400 + 4 * 3_600 + (System.currentTimeMillis() - startedAt) / 1000
        buildString {
            appendLine("host=demo-machine")
            appendLine("stat=cpu $cpuBusy 0 0 ${cpuTotal - cpuBusy} 0 0 0 0")
            appendLine("load=0.62 0.48 0.41")
            appendLine("ncpu=8")
            appendLine("MemTotal=16303216")
            appendLine("MemAvailable=${9_100_000 + Random.nextLong(-300_000, 300_000)}")
            appendLine("uptime=$uptime.42")
            appendLine("df=/dev/nvme0n1p2 490617784 201853012 263770548 44% /")
            appendLine("nvidia=NVIDIA RTX A4000, ${Random.nextInt(18, 64)}, ${Random.nextInt(5_200, 7_400)}, 16376")
        }
    }

    private fun listDirectories(path: String): String {
        var dir = resolve(HOME, path.ifEmpty { "~" })
        while (dir !in TREE && dir != "/") dir = dir.substringBeforeLast('/').ifEmpty { "/" }
        return buildString {
            appendLine(dir)
            TREE[dir].orEmpty().filter { it.dir }.forEach { appendLine((if (it.repo) "g/" else "d/") + it.name) }
        }
    }

    // ---- The demo session ------------------------------------------------------------------

    private fun seed() {
        val api = createSpace("api", "$HOME/src/api", focus = false)
        val apiMain = tabs.last().apply { label = "main" }
        val claude = panes.getValue(apiMain.panes.first())
        claude.agent = Agent("claude", "Claude", Span("✻ Claude", Style.ORANGE), "auth-refactor")
        claude.transcript += listOf(
            line(Span("> ", Style.BOLD), Span("Refactor the auth middleware to use the session store")),
            emptyList(),
            line(Span("⏺ ", Style.GREEN), Span("I'll move token lookups from the in-memory cache to the session store and add a migration for the sessions table.")),
            emptyList(),
            line(Span("⏺ ", Style.GREEN), Span("Update(src/middleware/auth.ts)", Style.BOLD)),
            line(Span("  ⎿  ", Style.DIM), Span("Updated with 14 additions and 6 removals")),
            line(Span("     + ", Style.GREEN), Span("const session = await store.get(token);", Style.GREEN)),
            line(Span("     - ", Style.RED), Span("const session = cache[token];", Style.RED)),
            line(Span("⏺ ", Style.GREEN), Span("Write(migrations/0007_sessions.sql)", Style.BOLD)),
            line(Span("  ⎿  ", Style.DIM), Span("Wrote 18 lines")),
            emptyList(),
            line(Span("⏺ ", Style.GREEN), Span("Now the migration has to run against staging.")),
        )
        claude.agent!!.apply {
            status = AgentStatus.BLOCKED
            title = "Wants to run: npm run migrate"
            permission = Permission(
                tool = "Bash command",
                command = "npm run migrate -- --env staging",
                workingTitle = "Running the staging migration",
                approved = listOf(
                    line(Span("⏺ ", Style.GREEN), Span("Bash(npm run migrate -- --env staging)", Style.BOLD)),
                    line(Span("  ⎿  ", Style.DIM), Span("Applied 0007_sessions.sql (12 ms)")),
                ),
                summary = listOf(
                    line(Span("⏺ ", Style.GREEN), Span("Done. Auth reads sessions from the store, the cache is gone and tests/auth.test.ts passes.")),
                ),
                doneTitle = "Auth middleware uses the session store",
            )
        }

        val codex = newPane(apiMain, api.cwd)
        codex.agent = Agent("codex", "Codex", Span("◆ Codex", Style.PURPLE), null).apply {
            status = AgentStatus.WORKING
            title = "Write integration tests"
        }
        codex.transcript += listOf(
            line(Span("> ", Style.BOLD), Span("Write integration tests for the sessions API")),
            emptyList(),
            line(Span("• ", Style.PURPLE), Span("Explored tests/ and src/routes/")),
            line(Span("• ", Style.PURPLE), Span("Wrote tests/sessions.test.ts")),
            line(Span("• ", Style.PURPLE), Span("Running npm test", Style.BOLD)),
        )

        val server = newPane(apiMain, api.cwd)
        server.label = "server"
        server.process = "npm run dev"
        server.transcript += listOf(
            prompt(server) + Span("npm run dev"),
            text("> api@1.4.0 dev", Style.DIM),
            text("> tsx watch src/server.ts", Style.DIM),
            emptyList(),
            line(Span("Listening on "), Span("http://localhost:3000", Style.BLUE)),
        )

        val logsTab = createTab(api, "logs", focus = false)
        val logs = panes.getValue(logsTab.panes.first())
        logs.process = "tail -f logs/app.log"
        logs.transcript += prompt(logs) + Span("tail -f logs/app.log")
        logs.transcript += logLine("info ", "worker started (pid 4121)")

        val web = createSpace("web", "$HOME/src/web", focus = false)
        val reviewer = panes.getValue(tabs.last { it.spaceId == web.id }.panes.first())
        reviewer.agent = Agent("claude", "Claude", Span("✻ Claude", Style.ORANGE), "reviewer").apply {
            status = AgentStatus.DONE
            title = "Reviewed PR #142: 3 comments"
        }
        reviewer.transcript += listOf(
            line(Span("> ", Style.BOLD), Span("Review PR #142, the checkout redesign")),
            emptyList(),
            line(Span("⏺ ", Style.GREEN), Span("Read the 14 changed files and left 3 comments on PR #142:")),
            line(Span("  1. ", Style.DIM), Span("components/Cart.tsx: memoize the totals selector")),
            line(Span("  2. ", Style.DIM), Span("app/checkout/page.tsx: handle an empty cart")),
            line(Span("  3. ", Style.DIM), Span("public/locales/es.json: two strings are missing")),
            emptyList(),
            line(Span("⏺ ", Style.GREEN), Span("It's ready to merge once those are addressed.")),
        )

        val infra = createSpace("infra", "$HOME/src/infra", focus = false)
        val pi = panes.getValue(tabs.last { it.spaceId == infra.id }.panes.first())
        pi.agent = Agent("pi", "Pi", Span("π Pi", Style.YELLOW), null).apply { title = "Waiting for instructions" }
        pi.transcript += listOf(
            line(Span("> ", Style.BOLD), Span("Summarize what changed in infra this week")),
            emptyList(),
            line(Span("π ", Style.YELLOW), Span("Three merged PRs: staging moved to e2-standard-4 nodes, the prod database got a read replica, and the CDN cache rules were tightened.")),
        )

        focus(claude)
        listOf(claude, codex, reviewer, pi).forEach { it.agent!!.seq = ++stateSeq }
        structureChanged = false
    }

    private fun startAmbient() {
        val codex = panes.values.first { it.agent?.kind == "codex" }
        val server = panes.values.first { it.label == "server" }
        val logs = panes.values.first { it.process?.startsWith("tail") == true }
        val pi = panes.values.first { it.agent?.kind == "pi" }

        codex.agent!!.job = scope.launch {
            for (step in CODEX_TESTS) {
                delay(2_500)
                synchronized(lock) {
                    if (panes[codex.id] !== codex) return@launch
                    codex.transcript += line(Span("    ✓ ", Style.GREEN), Span(step))
                    touch(codex)
                    flush()
                }
            }
            delay(1_500)
            synchronized(lock) {
                if (panes[codex.id] !== codex) return@launch
                codex.transcript += emptyList<Span>()
                codex.transcript += line(Span("• ", Style.PURPLE), Span("All 42 tests pass. Added ${CODEX_TESTS.size} integration tests for /sessions."))
                setStatus(codex.agent!!, AgentStatus.DONE, "42 tests pass")
                touch(codex)
                flush()
            }
        }

        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        scope.launch {
            var i = 0
            while (isActive) {
                delay(4_000)
                synchronized(lock) {
                    if (server.process != null && panes[server.id] === server) {
                        server.transcript += line(Span(time.format(Date()) + " ", Style.DIM), Span(REQUESTS[i++ % REQUESTS.size]))
                        touch(server)
                    }
                    if (logs.process != null && panes[logs.id] === logs && i % 2 == 0) {
                        val (level, message) = LOGS[(i / 2) % LOGS.size]
                        logs.transcript += logLine(level, message)
                        touch(logs)
                    }
                    flush()
                }
            }
        }

        // A while in, the infra agent gets a task from the desktop and stops to ask for permission.
        val piAgent = pi.agent!!
        piAgent.job = scope.launch {
            delay(40_000)
            synchronized(lock) {
                if (panes[pi.id] !== pi) return@launch
                pi.transcript += emptyList<Span>()
                pi.transcript += line(Span("> ", Style.BOLD), Span("Scale the staging node pool to 3 nodes"))
                setStatus(piAgent, AgentStatus.WORKING, "Planning the staging node pool change")
                touch(pi)
                flush()
            }
            delay(6_000)
            synchronized(lock) {
                if (panes[pi.id] !== pi) return@launch
                pi.transcript += emptyList<Span>()
                pi.transcript += line(Span("π ", Style.YELLOW), Span("terraform plan: 1 to change (module.staging.node_count 2 -> 3)."))
                piAgent.permission = Permission(
                    tool = "Bash command",
                    command = "terraform apply -target=module.staging",
                    workingTitle = "Applying the staging change",
                    approved = listOf(
                        line(Span("π ", Style.YELLOW), Span("Bash(terraform apply -target=module.staging)", Style.BOLD)),
                        line(Span("  ⎿  ", Style.DIM), Span("Apply complete! Resources: 0 added, 1 changed, 0 destroyed.")),
                    ),
                    summary = listOf(line(Span("π ", Style.YELLOW), Span("Staging now runs 3 nodes."))),
                    doneTitle = "Staging pool scaled to 3 nodes",
                )
                setStatus(piAgent, AgentStatus.BLOCKED, "Wants to run: terraform apply")
                touch(pi)
                flush()
            }
        }
    }

    private fun logLine(level: String, message: String): Line = line(
        Span(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " ", Style.DIM),
        Span(level, if (level.startsWith("warn")) Style.YELLOW else Style.GREEN),
        Span(" $message"),
    )

    private class Entry(val name: String, val dir: Boolean = false, val repo: Boolean = false)

    companion object {
        const val HOME = "/home/demo"
        private const val MAX_TRANSCRIPT = 400
        private val TERMINAL = Regex("""terminal session (control|observe) (\S+) --cols (\d+) --rows (\d+)""")
        private val KEYS = mapOf("Enter" to "\r", "Escape" to "\u001b", "Tab" to "\t", "Backspace" to "\u007f", "C-c" to "\u0003")

        private val CODEX_TESTS = listOf(
            "creates a session on login (38 ms)",
            "rejects an expired token (12 ms)",
            "refreshes a session before expiry (21 ms)",
            "revokes every session on password change (44 ms)",
            "lists a user's active sessions (17 ms)",
            "paginates sessions (19 ms)",
            "ignores sessions from other users (11 ms)",
            "returns 404 for an unknown session (6 ms)",
            "logs out the current session (14 ms)",
        )
        private val REQUESTS = listOf(
            "GET /health 200 2ms", "POST /sessions 201 18ms", "GET /users/42 200 7ms",
            "GET /sessions/current 200 4ms", "DELETE /sessions/9f3 204 9ms",
        )
        private val LOGS = listOf(
            "info " to "job sessions.cleanup finished (0 expired)",
            "warn " to "slow query sessions.by_token 212ms",
            "info " to "cache hit ratio 0.93",
        )

        private fun dirs(vararg names: String) = names.map { Entry(it, dir = true) }
        private fun repos(vararg names: String) = names.map { Entry(it, dir = true, repo = true) }
        private fun files(vararg names: String) = names.map { Entry(it) }

        private val TREE: Map<String, List<Entry>> = mapOf(
            "/" to dirs("home", "tmp"),
            "/home" to dirs("demo"),
            "/tmp" to emptyList(),
            HOME to dirs(".config", "notes", "src") + files(".bashrc"),
            "$HOME/.config" to dirs("herdr"),
            "$HOME/.config/herdr" to files("config.toml"),
            "$HOME/notes" to files("todo.md"),
            "$HOME/src" to repos("api", "infra", "web"),
            "$HOME/src/api" to dirs("logs", "migrations", "src", "tests") + files("package.json", "README.md"),
            "$HOME/src/api/logs" to files("app.log"),
            "$HOME/src/api/migrations" to files("0006_users.sql", "0007_sessions.sql"),
            "$HOME/src/api/src" to dirs("middleware", "routes") + files("server.ts"),
            "$HOME/src/api/src/middleware" to files("auth.ts", "session.ts"),
            "$HOME/src/api/src/routes" to files("health.ts", "sessions.ts", "users.ts"),
            "$HOME/src/api/tests" to files("auth.test.ts", "sessions.test.ts"),
            "$HOME/src/web" to dirs("app", "components", "public") + files("package.json"),
            "$HOME/src/web/app" to dirs("checkout") + files("layout.tsx", "page.tsx"),
            "$HOME/src/web/app/checkout" to files("page.tsx"),
            "$HOME/src/web/components" to files("Cart.tsx", "Header.tsx"),
            "$HOME/src/web/public" to dirs("locales"),
            "$HOME/src/web/public/locales" to files("en.json", "es.json"),
            "$HOME/src/infra" to dirs("envs", "modules") + files("main.tf"),
            "$HOME/src/infra/envs" to files("prod.tfvars", "staging.tfvars"),
            "$HOME/src/infra/modules" to dirs("prod", "staging"),
            "$HOME/src/infra/modules/prod" to files("main.tf"),
            "$HOME/src/infra/modules/staging" to files("main.tf"),
        )

        fun abbreviate(path: String) = when {
            path == HOME -> "~"
            path.startsWith("$HOME/") -> "~" + path.removePrefix(HOME)
            else -> path
        }

        /** [path] relative to [cwd], with `~` and `..` resolved. */
        fun resolve(cwd: String, path: String?): String {
            val raw = when {
                path.isNullOrBlank() -> cwd
                path == "~" -> HOME
                path.startsWith("~/") -> HOME + path.substring(1)
                path.startsWith("/") -> path
                else -> "$cwd/$path"
            }
            val parts = ArrayList<String>()
            raw.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts += part
                }
            }
            return "/" + parts.joinToString("/")
        }

        /** The last word of a `sh -c '…' sh <arg>` command line, unquoted. */
        fun lastArgument(command: String): String {
            val arg = command.substringAfterLast(" sh ")
            return if (arg.length >= 2 && arg.startsWith("'") && arg.endsWith("'")) {
                arg.substring(1, arg.length - 1).replace("'\\''", "'")
            } else {
                arg
            }
        }
    }
}
