package dev.herdroid.demo

import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.DirListing
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.HostProbe
import dev.herdroid.herdr.RemoteCommand
import dev.herdroid.herdr.TerminalEvent
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DemoHerdrTest {
    private val herdr = DemoHerdr(ambient = false)
    private val shell = DemoShell(herdr)
    private val api = HerdrApi(shell, "default", null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        scope.cancel()
        shell.close()
    }

    @Test(timeout = 10_000) fun snapshotHasSpacesAgentsAndLayouts(): Unit = runBlocking {
        val snapshot = api.snapshot()
        assertEquals(listOf("api", "web", "infra"), snapshot.workspaces.map { it.label })
        assertEquals(AgentStatus.BLOCKED, snapshot.workspaces.first().agentStatus)
        assertEquals(
            listOf(AgentStatus.BLOCKED, AgentStatus.WORKING, AgentStatus.DONE, AgentStatus.IDLE),
            snapshot.agents.map { it.agentStatus },
        )
        assertEquals(snapshot.agents.first().paneId, snapshot.focusedPaneId)
        snapshot.tabs.forEach { tab -> assertEquals(tab.paneCount, snapshot.layout(tab.tabId)!!.panes.size) }
        assertEquals("server", snapshot.panes.first { it.label == "server" }.displayTitle)
    }

    @Test(timeout = 10_000) fun structureActionsChangeTheSnapshot(): Unit = runBlocking {
        api.createWorkspace("docs", "~/notes", focus = true)
        var snapshot = api.snapshot()
        val docs = snapshot.workspaces.single { it.label == "docs" }
        val shellPane = snapshot.panes.single { it.workspaceId == docs.workspaceId }
        assertEquals("${DemoHerdr.HOME}/notes", shellPane.cwd)
        assertEquals(shellPane.paneId, snapshot.focusedPaneId)

        val split = api.splitPane(shellPane.paneId, "right")!!
        api.createTab(docs.workspaceId, "more")
        api.renameWorkspace(docs.workspaceId, "notes")
        snapshot = api.snapshot()
        assertEquals("notes", snapshot.workspace(docs.workspaceId)!!.label)
        assertEquals(3, snapshot.workspace(docs.workspaceId)!!.paneCount)
        assertEquals(listOf(shellPane.paneId, split.paneId), snapshot.panesOf(shellPane.tabId).map { it.paneId })

        api.closePane(split.paneId)
        api.closeWorkspace(docs.workspaceId)
        snapshot = api.snapshot()
        assertNull(snapshot.workspace(docs.workspaceId))
        assertNotNull(snapshot.pane(snapshot.focusedPaneId!!))

        val error = runCatching { api.closeTab("missing") }.exceptionOrNull()
        assertEquals("not_found", (error as dev.herdroid.herdr.HerdrApiException).code)
    }

    @Test(timeout = 10_000) fun answeringAPermissionPromptRunsTheAgentToCompletion(): Unit = runBlocking {
        val blocked = api.snapshot().agents.first { it.agentStatus == AgentStatus.BLOCKED }
        val events = async(Dispatchers.IO) { api.subscribe(HerdrApi.LIFECYCLE_EVENTS).first() }
        withTimeout(3_000) { while (herdr.subscriberCount == 0) delay(10) }
        val stream = TerminalStream.open(shell, scope, "default", null, blocked.paneId, 60, 30, takeover = false, readOnly = false)
        val frames = stream.events
        val emulator = TerminalEmulator(60, 30)
        val first = withTimeout(3_000) { frames.first() } as TerminalEvent.Frame
        emulator.feed(first.bytes)
        assertTrue(emulator.screenText().contains("Do you want to proceed?"))

        stream.input("1")
        withTimeout(3_000) { events.await() }
        assertEquals(AgentStatus.WORKING, api.snapshot().agentFor(blocked.paneId)!!.agentStatus)
        withTimeout(6_000) {
            while (api.snapshot().agentFor(blocked.paneId)!!.agentStatus != AgentStatus.DONE) delay(100)
        }
        stream.close()
    }

    @Test(timeout = 10_000) fun promptsGoToIdleAgentsAndShellsRunCommands(): Unit = runBlocking {
        val snapshot = api.snapshot()
        val pi = snapshot.agents.first { it.agentStatus == AgentStatus.IDLE }
        api.promptAgent(pi.paneId, "Deploy the docs")
        assertEquals(AgentStatus.WORKING, api.snapshot().agentFor(pi.paneId)!!.agentStatus)
        assertTrue(api.readPane(pi.paneId).contains("> Deploy the docs"))

        api.createWorkspace("scratch", null)
        val pane = api.snapshot().panes.last()
        api.sendText(pane.paneId, "cd src/api\r")
        api.sendText(pane.paneId, "ls\r")
        assertEquals("${DemoHerdr.HOME}/src/api", api.snapshot().pane(pane.paneId)!!.cwd)
        assertTrue(api.readPane(pane.paneId).contains("migrations"))
    }

    @Test(timeout = 10_000) fun hostCommandsAnswerLikeAMachine(): Unit = runBlocking {
        suspend fun run(command: String) = shell.exec(command).stdout.readBytes().toString(Charsets.UTF_8)

        assertEquals(DemoHerdr.HOME, run("/bin/sh -c 'printf %s \"\$HOME\"'"))
        val listing = DirListing.parse(run(RemoteCommand.listDirectories("~/src/missing/deeper")))!!
        assertEquals("${DemoHerdr.HOME}/src", listing.path)
        assertTrue(listing.entries.all { it.isRepo })
        assertEquals(listOf("api", "infra", "web"), listing.entries.map { it.name })

        val sample = HostProbe.parse(run(HostProbe.command()))!!
        assertEquals("demo-machine", sample.hostname)
        assertNotNull(sample.memoryUsed)
        assertNotNull(sample.diskFree)

        val upload = shell.exec(RemoteCommand.uploadFile("it's.png"))
        upload.stdin.use { it.write(ByteArray(4096) { 10 }) }
        assertEquals("/tmp/herdroid-1000/it's.png", upload.stdout.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun pathsResolveLikeAShell() {
        assertEquals(DemoHerdr.HOME, DemoHerdr.resolve("/tmp", "~"))
        assertEquals("${DemoHerdr.HOME}/src", DemoHerdr.resolve("${DemoHerdr.HOME}/src/api", ".."))
        assertEquals("/", DemoHerdr.resolve("/", "../.."))
        assertEquals("~/src/api", DemoHerdr.abbreviate("${DemoHerdr.HOME}/src/api"))
    }

    @Test fun wrapBreaksAtSpacesAndKeepsStyles() {
        val rows = DemoScreen.wrap(listOf(Span("> ", Style.BOLD), Span("hello wide world")), 10)
        assertEquals(listOf("> hello", "wide world"), rows.map { row -> row.joinToString("") { it.text } })
        assertEquals(Style.BOLD, rows.first().first().style)
        assertTrue(rows.all { DemoScreen.width(it) <= 10 })
    }
}
