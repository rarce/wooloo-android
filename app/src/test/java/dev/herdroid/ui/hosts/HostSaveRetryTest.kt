package dev.herdroid.ui.hosts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.WoolooMobileApp
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = WoolooMobileApp::class)
class HostSaveRetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun credentialFailureRetainsDraftAndDoesNotPublishIncompleteMachine() {
        // Robolectric has no Android Keystore, so entering a saved password exercises a credential failure.
        val app = RuntimeEnvironment.getApplication() as WoolooMobileApp
        var navigations = 0
        compose.setContent {
            WoolooMobileTheme {
                HostEditScreen(null, onDone = { _, _ -> navigations++ }, onImportKey = {})
            }
        }
        compose.onNodeWithText("Host", substring = false).performTextInput("dev.example.test")
        compose.onNodeWithText("User", substring = false).performTextInput("alice")
        compose.onNodeWithText("Password", substring = false).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Password", substring = false)).performTextInput("retained secret")
        val failure = "Could not save this machine. Your changes are still here."
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.hosts.hosts.value.isEmpty())
        compose.onNodeWithText("dev.example.test").assertExists()
        compose.onNodeWithText("alice").assertExists()
        compose.runOnIdle { assertEquals(0, navigations) }
        // Choose to be prompted at connect time instead of saving a password, then retry.
        compose.onNode(hasSetTextAction() and hasText("Password", substring = false)).performTextClearance()
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(5000) { navigations == 1 }
        compose.runOnIdle {
            assertEquals("dev.example.test", app.hosts.hosts.value.single().hostname)
            assertEquals("alice", app.hosts.hosts.value.single().username)
            assertEquals(1, navigations)
        }
    }
}
