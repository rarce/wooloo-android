package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.herdr.*
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
}
