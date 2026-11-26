package dev.herdroid.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun finalReviewButtonsSendTheNumberOfTheChosenOption() {
        val keys = mutableListOf<String>()
        val options = parsePromptOptions(sequenceOf(" ❯ 1. Submit answers", "   2. Cancel"))
        compose.setContent { WoolooMobileTheme { QuickAnswers(options) { keys.add(it) } } }
        compose.onNodeWithText("3").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Submit answers").performClick()
        assertEquals(listOf("2", "1"), keys)
    }

    @Test fun watchingBarSaysWhoHasControlAndWhatTakingItDoes() {
        var moved by mutableStateOf(true)
        var taken = 0
        compose.setContent { WoolooMobileTheme { WatchingBar(moved, onTakeControl = { taken++ }) } }
        compose.onNodeWithText("Another screen has control").assertExists()
        compose.onNodeWithText("Taking control resizes the pane to fit this phone.").assertExists()
        compose.onNodeWithText("Take control").performClick()
        moved = false
        compose.onNodeWithText("Watching only").assertExists()
        compose.runOnIdle { assertEquals(1, taken) }
    }

    @Test fun fallbackOffersOneToThree() {
        val keys = mutableListOf<String>()
        compose.setContent { WoolooMobileTheme { QuickAnswers { keys.add(it) } } }
        compose.onNodeWithText("4").assertDoesNotExist()
        // The filled 1 is only a default: TalkBack mustn't call it highlighted in the prompt.
        compose.onNodeWithText("1")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText("3").performClick()
        assertEquals(listOf("3"), keys)
    }

    @Test fun everyParsedOptionGetsAButtonThatScrollsIntoReach() {
        val keys = mutableListOf<String>()
        val options = parsePromptOptions(sequenceOf(
            "❯ 1. Postgres", "  2. MySQL", "  3. SQLite", "  4. DuckDB", "  5. Type something.", "  6. Submit",
        ))
        compose.setContent { WoolooMobileTheme { QuickAnswers(options) { keys.add(it) } } }
        (1..6).forEach { compose.onNodeWithText(it.toString()).assertExists() }
        compose.onNodeWithText("6").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("5").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("1").performScrollTo().assertIsDisplayed().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Highlighted in the prompt"))
            .performClick()
        compose.onNodeWithText("2").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsNotSelected()
        assertEquals(listOf("6", "5", "1"), keys)
    }

    @Test fun largeFontHeaderKeepsSpaceStatusAndMetricsInsideItsBounds() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                WoolooMobileTheme {
                    Column(Modifier.fillMaxWidth().testTag("header")) {
                        TerminalTopBar(
                            pane = PaneInfo("p", workspaceId = "w", tabId = "t", title = "Review fixture", cwd = "/tmp/review"),
                            snapshot = SessionSnapshot(workspaces = listOf(WorkspaceInfo("w", label = "Workspace")),
                                tabs = listOf(TabInfo("t", "w", label = "Shell"))),
                            agentName = "Claude", agentStatus = AgentStatus.BLOCKED, agentLabel = "blocked", onBack = {},
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
        compose.onNodeWithText("Workspace › Shell › review").assertDoesNotExist()
        val title = compose.onNodeWithText("Workspace").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText("blocked").fetchSemanticsNode().boundsInRoot.top >= title.bottom)
        for (text in listOf("Workspace", "CPU 25% · RAM 50%")) {
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

    @Test fun onlyShortensSpaceNamesAtASeparator() {
        assertEquals("auth-refactor", paneSwitcherLabel("API / auth-refactor", "api"))
        assertEquals("main", paneSwitcherLabel("api-main", "api"))
        assertEquals("api", paneSwitcherLabel("api", "api"))
        assertEquals("api-", paneSwitcherLabel("api-", "api"))
        assertEquals("apiculture", paneSwitcherLabel("apiculture", "api"))
        assertEquals("server", paneSwitcherLabel("server", "api"))
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
