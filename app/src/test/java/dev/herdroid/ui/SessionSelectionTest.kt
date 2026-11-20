package dev.herdroid.ui

import androidx.compose.foundation.layout.Column
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.data.HostProfile
import dev.herdroid.ui.hosts.HostItem
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.herdr.*
import dev.herdroid.screenshots.SampleData
import dev.herdroid.ui.session.SessionDialog
import dev.herdroid.ui.terminal.PaneSwitcher
import dev.herdroid.ui.session.SpacesContent
import dev.herdroid.ui.session.SpaceContent
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
class SessionSelectionTest {
    @get:Rule val compose = createComposeRule()
    private val workspace = WorkspaceInfo("w1", label = "api", tabCount = 1, paneCount = 1)
    private val tab = TabInfo("t1", "w1", label = "1", paneCount = 1)
    private val pane = PaneInfo("p1", workspaceId = "w1", tabId = "t1", agent = "codex")
    private val agent = AgentInfo("p1", workspaceId = "w1", tabId = "t1", agent = "codex", name = "reviewer",
        agentStatus = AgentStatus.BLOCKED, title = "Review changes")
    private val snapshot = SessionSnapshot(workspaces = listOf(workspace), tabs = listOf(tab),
        panes = listOf(pane), agents = listOf(agent))

    @Test fun statsLoadingDuringTapKeepsSecondSpaceUnderTheFinger() {
        val loaded = androidx.compose.runtime.mutableStateOf(false)
        var opened: String? = null
        val spaces = snapshot.copy(workspaces = listOf(workspace, workspace.copy(workspaceId = "w2", label = "web")))
        compose.setContent { WoolooMobileTheme {
            SpacesContent(spaces, false, { opened = it }, {}) {
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(if (loaded.value) 180.dp else 12.dp))
            }
        } }
        val row = compose.onNodeWithText("web")
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performTouchInput { down(center) }
        compose.runOnIdle { loaded.value = true }
        assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
        row.performTouchInput { up() }
        assertEquals("w2", opened)
    }

    @Test fun firstMachineReceivingSnapshotKeepsSecondMachineInPlace() {
        val data = androidx.compose.runtime.mutableStateOf<SessionSnapshot?>(null)
        val hosts = listOf("First", "Second").map { HostProfile(label = it, hostname = "example.invalid") }
        compose.setContent { WoolooMobileTheme {
            Column {
                hosts.forEachIndexed { index, host ->
                    HostItem(host, ConnectionStatus.Connected, if (index == 0) data.value else null,
                        true, {}, {}, {}, {})
                }
            }
        } }
        val second = compose.onNodeWithText("Second")
        val original = second.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { data.value = snapshot }
        assertEquals(original, second.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun spacesOpenOnlySelectedWorkspaceAndExposeActions() {
        var opened: String? = null
        compose.setContent { WoolooMobileTheme {
            SpacesContent(snapshot, false, { opened = it }, {})
        } }
        compose.onNodeWithText("api").performClick()
        assertEquals("w1", opened)
        compose.onNodeWithContentDescription("Actions for api").performClick()
        compose.onNodeWithText("Rename space").assertIsDisplayed()
        compose.onNodeWithText("New tab").assertIsDisplayed()
        compose.onNodeWithText("Close space").assertIsDisplayed()
    }

    @Test fun lateAgentDetailsKeepOtherAgentAndTabPositions() {
        val first = agent.copy(title = null)
        val second = agent.copy(paneId = "p2", name = "second")
        val data = androidx.compose.runtime.mutableStateOf(snapshot.copy(
            agents = listOf(first, second), panes = listOf(pane, pane.copy(paneId = "p2")),
            tabs = listOf(tab, tab.copy(tabId = "t2", label = "Shells")),
        ))
        compose.setContent { WoolooMobileTheme {
            SpaceContent(data.value, "w1", false, {}, {}, {}, {}, { _, _ -> })
        } }
        val targets = listOf("second", "Shells").map { compose.onNodeWithText(it) }
        val original = targets.map { it.fetchSemanticsNode().boundsInRoot }
        compose.runOnIdle {
            data.value = data.value.copy(agents = listOf(first.copy(title = "Updated task", agentStatus = AgentStatus.DONE), second))
        }
        assertEquals(original, targets.map { it.fetchSemanticsNode().boundsInRoot })
    }

    @Test fun agentsOpenOnlyTheirPaneAndReplyActionUsesSamePane() {
        var opened: String? = null
        compose.setContent { WoolooMobileTheme {
            SpaceContent(snapshot, "w1", false, { opened = it }, {}, { opened = it.paneId }, {}, { _, _ -> })
        } }
        compose.onNodeWithText("reviewer").performClick()
        assertEquals("p1", opened)
        opened = null
        compose.onNodeWithContentDescription("Message reviewer").performClick()
        assertEquals("p1", opened)
        compose.onNodeWithText("Review changes").assertExists()
    }

    /** Rows are the long-pressable list entries; layout miniature tiles only take taps. */
    private fun rowTitles(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).fetchSemanticsNodes()
            .map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().first().text }

    @Test fun spaceListsEveryPaneOnceGroupedByTabInLayoutOrder() {
        val snap = SampleData.snapshot
        var opened: String? = null
        var menuFor: String? = null
        var dialog: SessionDialog? = null
        compose.setContent { WoolooMobileTheme {
            SpaceContent(snap, "w1", false, { opened = it }, { menuFor = it.paneId }, {}, { dialog = it }, { _, _ -> })
        } }
        // Same order as the terminal's swipe and switcher: main's agents and shell, then logs.
        val expected = snap.panesInLayoutOrder("w1").map { snap.agentFor(it.paneId)?.displayName ?: it.displayTitle }
        assertEquals(listOf("auth-refactor", "Codex", "server", "log"), expected)
        assertEquals(expected, rowTitles())
        compose.onNodeWithText("main").assertIsDisplayed()
        compose.onNodeWithText("logs").assertIsDisplayed()
        // The split tab's miniature is one accessibility node, so each pane is announced once (as its row).
        compose.onNodeWithContentDescription("Layout of main, 3 panes").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("server").fetchSemanticsNodes().size)

        compose.onAllNodes(hasText("server") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .onFirst().performClick()
        assertEquals("w1:p3", opened)
        compose.onAllNodes(hasText("server") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .onFirst().performTouchInput { longClick() }
        assertEquals("w1:p3", menuFor)

        compose.onNodeWithContentDescription("Actions for logs").performClick()
        compose.onNodeWithText("Split right").assertIsDisplayed()
        compose.onNodeWithText("Split down").assertIsDisplayed()
        compose.onNodeWithText("Rename tab").assertIsDisplayed()
        compose.onNodeWithText("Close tab").performClick()
        assertEquals(SessionDialog.CloseTab(snap.tab("w1:t2")!!), dialog)
    }

    @Test fun loneUnnamedTabIsAPlainListThatKeepsTheTabMenu() {
        compose.setContent { WoolooMobileTheme {
            SpaceContent(snapshot, "w1", false, {}, {}, {}, {}, { _, _ -> })
        } }
        compose.onNodeWithText("Panes").assertIsDisplayed()
        compose.onNodeWithText("Tab 1").assertDoesNotExist()
        assertEquals(listOf("reviewer"), rowTitles())
        compose.onNodeWithContentDescription("Actions for Tab 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tab actions").performClick()
        compose.onNodeWithText("Close tab").assertIsDisplayed()
    }

    @Test fun spaceWithoutAgentsExplainsItAndStillListsShells() {
        val shell = pane.copy(agent = null)
        val shells = snapshot.copy(panes = listOf(shell), agents = emptyList())
        var opened: String? = null
        compose.setContent { WoolooMobileTheme {
            SpaceContent(shells, "w1", false, { opened = it }, {}, {}, {}, { _, _ -> })
        } }
        compose.onNodeWithText("No agents here yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText(shell.displayTitle).performClick()
        assertEquals("p1", opened)
    }

    @Test fun switcherShowsTabBoundariesInTheSameOrder() {
        val snap = SampleData.snapshot
        var selected: String? = null
        compose.setContent { WoolooMobileTheme {
            PaneSwitcher(snap, snap.pane("w1:p1")!!) { selected = it }
        } }
        compose.onNodeWithText("main").assertIsDisplayed()
        compose.onNodeWithText("server").performClick()
        assertEquals("w1:p3", selected)
        compose.onNode(hasScrollAction()).performScrollToKey("tab-w1:t2")
        compose.onNodeWithText("logs").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToKey("w1:p4")
        compose.onNodeWithText("log").performClick()
        assertEquals("w1:p4", selected)
    }
}
