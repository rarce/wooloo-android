package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = android.app.Application::class)
class ComposerRetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSendRetainsDraftAndSuccessDismissesOnce() {
        val sent = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            WoolooMobileTheme {
                ComposerSheet("Message", null, true, onDismiss = { dismissed++ }, onQuickReply = {},
                    onSend = { text, _, _ ->
                        sent += text
                        if (sent.size == 1) Result.failure(IOException("Network unavailable"))
                        else Result.success(Unit)
                    })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("keep this draft")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNodeWithText("Network unavailable").assertExists()
        compose.onNodeWithText("keep this draft").assertExists()
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5000) { dismissed == 1 }
        compose.runOnIdle { assertEquals(listOf("keep this draft", "keep this draft"), sent) }
    }
}
