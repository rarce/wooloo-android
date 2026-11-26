package dev.herdroid.ui.terminal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.components.agentQuickReplies
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
class ComposerAnswersTest {
    @get:Rule val compose = createComposeRule()

    private val six = parsePromptOptions(sequenceOf(
        "❯ 1. Postgres", "  2. MySQL", "  3. SQLite", "  4. DuckDB", "  5. Type something.", "  6. Submit",
    ))

    @Test fun promptOptionsReplaceOneToThreeAndKeepTheOtherKeys() {
        val labels = agentQuickReplies(six.map { it.asQuickReply() }).map { it.label }
        assertEquals(
            listOf("⏎ Enter", "1 Postgres", "2 MySQL", "3 SQLite", "4 DuckDB", "5 Type something.", "6 Submit",
                "y", "n", "Esc", "⇧Tab", "Ctrl‑C"),
            labels,
        )
        assertEquals((1..6).map { "$it" }, six.map { it.asQuickReply().input })
        assertEquals(listOf("1", "2", "3"), agentQuickReplies().map { it.label }.filter { it.length == 1 && it[0].isDigit() })
    }

    @Test fun chipsSendTheOptionsDigit() {
        val sent = mutableListOf<String>()
        compose.setContent {
            WoolooMobileTheme {
                ComposerSheet("Message", null, true, onDismiss = {}, onQuickReply = { sent += it.input },
                    onSend = { _, _, _ -> Result.success(Unit) }, answers = six.map { it.asQuickReply() })
            }
        }
        compose.onNodeWithText("1 Postgres").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("6 Submit"))
        compose.onNodeWithText("6 Submit").performClick()
        compose.runOnIdle { assertEquals(listOf("1", "6"), sent) }
    }

    @Test fun withoutOptionsTheChipsStayOneToThree() {
        val sent = mutableListOf<String>()
        compose.setContent {
            WoolooMobileTheme {
                ComposerSheet("Message", null, true, onDismiss = {}, onQuickReply = { sent += it.input },
                    onSend = { _, _, _ -> Result.success(Unit) })
            }
        }
        listOf("1", "2", "3").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("2").performClick()
        compose.runOnIdle { assertEquals(listOf("2"), sent) }
    }


    private fun watching(isAgent: Boolean, sent: MutableList<String>, sentKeys: MutableList<String>,
                         live: Boolean = true, takeControl: () -> Unit) {
        compose.setContent {
            WoolooMobileTheme {
                ComposerSheet("Message", null, isAgent, onDismiss = {}, onQuickReply = { sentKeys += it.input },
                    onSend = { text, _, _ -> sent += text; Result.success(Unit) },
                    watchOnly = true, canTakeControl = live, onTakeControl = takeControl)
            }
        }
    }

    @Test fun watchingDisablesKeysButStillMessagesAnAgent() {
        val sent = mutableListOf<String>()
        val keys = mutableListOf<String>()
        var takeControl = 0
        watching(isAgent = true, sent, keys) { takeControl++ }
        compose.onNodeWithText("⏎ Enter").assertIsNotEnabled()
        compose.onNodeWithText("1").assertIsNotEnabled()
        compose.onNodeWithText("Watching only, take control to send keys.").assertExists()
        compose.onNodeWithText("Take control").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("carry on")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        compose.waitUntil(5000) { sent.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, takeControl)
            assertEquals(listOf("carry on"), sent)
            assertEquals(emptyList<String>(), keys)
        }
    }

    @Test fun watchingAShellCantSend() {
        val sent = mutableListOf<String>()
        watching(isAgent = false, sent, mutableListOf()) {}
        compose.onNodeWithText("Ctrl‑C").assertIsNotEnabled()
        compose.onNodeWithText("Watching only, take control to send keys.").assertExists()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithText("Watching only: take control to run commands here.").assertExists()
        compose.onNodeWithText("Runs in this pane when you send.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Attach image").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("ls")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test fun takeControlIsOfferedOnlyForALivePane() {
        watching(isAgent = true, mutableListOf(), mutableListOf(), live = false) {}
        compose.onNodeWithText("Watching only, take control to send keys.").assertExists()
        compose.onNodeWithText("Take control").assertDoesNotExist()
        compose.onNodeWithContentDescription("Attach image").assertIsEnabled()
    }

    @Test fun inControlTheKeysWork() {
        val keys = mutableListOf<String>()
        compose.setContent {
            WoolooMobileTheme {
                ComposerSheet("Send", null, false, onDismiss = {}, onQuickReply = { keys += it.input },
                    onSend = { _, _, _ -> Result.success(Unit) })
            }
        }
        compose.onNodeWithText("Take control").assertDoesNotExist()
        compose.onNodeWithText("Ctrl‑C").assertIsEnabled().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ls")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("\u0003"), keys) }
    }
}
