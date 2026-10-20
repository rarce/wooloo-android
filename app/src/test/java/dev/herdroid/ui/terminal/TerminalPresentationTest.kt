package dev.herdroid.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.herdroid.herdr.*
import dev.herdroid.terminal.TerminalModel
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeFontHeaderKeepsBreadcrumbAndMetricsInsideItsBounds() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                WoolooMobileTheme {
                    Column(Modifier.fillMaxWidth().testTag("header")) {
                        TerminalTopBar(
                            pane = PaneInfo("p", workspaceId = "w", tabId = "t", title = "Review fixture", cwd = "/tmp/review"),
                            snapshot = SessionSnapshot(workspaces = listOf(WorkspaceInfo("w", label = "Workspace")),
                                tabs = listOf(TabInfo("t", "w", label = "Shell"))),
                            agentName = null, agentStatus = null, agentLabel = null, onBack = {},
                            menu = false, onMenu = {}, readOnly = false, onToggleReadOnly = {},
                            onFont = {}, onCopy = {}, onZoom = {}, onSplit = {}, onRename = {},
                            onClosePane = {}, onReconnect = {},
                            metrics = { PaneMetricsContent(HostStats(HostSample("fixture", memoryUsed = 50, memoryTotal = 100), 0.25), null) },
                        )
                    }
                }
            }
        }
        if (System.getProperty("herdroid.screenshots") == "true") {
            compose.onRoot().captureRoboImage("build/screenshots/terminal-header-font200.png")
        }
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        for (text in listOf("Review fixture", "Workspace › Shell › review", "CPU 25% · RAM 50%")) {
            val node = compose.onNodeWithText(text).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(text, bounds.top >= header.top && bounds.bottom <= header.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(text, (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
            assertTrue(text, bounds.height + 1f >= layout.size.height)
        }
    }

    @Test fun transcriptIsStableUntilRefreshAndHistoryFailureIsRetryable() {
        val model = TerminalModel().apply { applyFrame(80, 24, true, "first output".toByteArray()) }
        var calls = 0
        var dismissed = false
        compose.setContent {
            WoolooMobileTheme {
                TerminalTextDialog(model, loadHistory = {
                    calls++
                    if (calls == 1) Result.failure(Exception("Offline fixture")) else Result.success("older output")
                }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("first output", substring = true).assertExists()
        compose.runOnIdle { model.applyFrame(80, 24, true, "refreshed fixture".toByteArray()) }
        compose.onNodeWithText("refreshed fixture", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Refresh screen").performClick()
        compose.onNodeWithText("refreshed fixture", substring = true).assertExists()
        compose.onNodeWithText("Read recent output (400 lines)").performClick()
        compose.onNodeWithText("Offline fixture").assertIsDisplayed()
        compose.onNodeWithText("refreshed fixture", substring = true).assertExists()
        compose.onNodeWithText("Read recent output (400 lines)").performClick()
        compose.onNodeWithText("older output").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
