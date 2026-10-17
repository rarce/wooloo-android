package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.herdroid.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp")
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editingDraftDoesNotCreateMachineAndNavigationReturnsToSelection() {
        compose.onNodeWithText("Machines").assertIsDisplayed()
        compose.onNodeWithText("Add a machine", substring = true).performClick()
        compose.onNodeWithText("Host").performTextInput("example.invalid")
        compose.onNodeWithText("User").performTextInput("alice")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add your first machine").assertIsDisplayed()
        compose.onNodeWithText("Add a machine", substring = true).performClick()
        compose.onNodeWithText("example.invalid").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Terminal text size").assertIsDisplayed()
        compose.onNodeWithText("Distinct status symbols").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("SSH keys").performClick()
        compose.onNodeWithText("No keys yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Machines").assertIsDisplayed()
    }
}
