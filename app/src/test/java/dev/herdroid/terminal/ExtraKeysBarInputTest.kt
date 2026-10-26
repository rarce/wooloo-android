package dev.herdroid.terminal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import dev.herdroid.ui.terminal.ExtraKeysBar
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
class ExtraKeysBarInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageAndConversationButtonsAreVisibleAndOpenTheirOwnActions() {
        var attachments = 0
        var conversations = 0
        compose.setContent {
            WoolooMobileTheme {
                ExtraKeysBar(ModifierState(), false,
                    onSpecial = {}, onText = {}, onPaste = {}, onToggleKeyboard = {},
                    onComposer = { conversations++ }, composerHighlighted = false,
                    onAttachImage = { attachments++ })
            }
        }
        compose.onNodeWithContentDescription("Attach images").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, attachments); assertEquals(0, conversations) }
        compose.onNodeWithContentDescription("Compose prompt").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, attachments); assertEquals(1, conversations) }
    }

    @Test fun rapidShiftThenLeftWorksAndPaneSwitchUsesNewModifiers() {
        val first = ModifierState()
        val current = mutableStateOf(first)
        val output = mutableListOf<String>()
        compose.setContent {
            val modifiers = current.value
            WoolooMobileTheme(darkTheme = true) {
                ExtraKeysBar(modifiers, false,
                    onSpecial = { key ->
                        val (ctrl, alt, shift) = modifiers.consume()
                        output.add(KeyEncoder.special(key, ctrl, alt, shift))
                    },
                    onText = {}, onPaste = {}, onToggleKeyboard = {}, onComposer = {},
                    composerHighlighted = false)
            }
        }
        compose.onNodeWithText("shift").performTouchInput { click() }
        compose.runOnIdle { assertTrue(first.shift) }
        compose.onNodeWithText("←").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf("\u001b[1;2D"), output)
            assertFalse(first.shift)
        }
        val second = ModifierState()
        compose.runOnIdle { current.value = second }
        compose.onNodeWithText("shift").performTouchInput { click() }
        compose.runOnIdle { assertTrue(second.shift); assertFalse(first.shift) }
        compose.onNodeWithText("←").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("\u001b[1;2D", "\u001b[1;2D"), output) }
    }
}
