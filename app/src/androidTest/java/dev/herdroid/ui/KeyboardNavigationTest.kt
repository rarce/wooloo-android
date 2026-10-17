package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import dev.herdroid.MainActivity
import org.junit.Rule
import org.junit.Test

class KeyboardNavigationTest {
    @get:Rule(order = 0) val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun keyboardVisible(): Boolean {
        var visible = false
        compose.activityRule.scenario.onActivity {
            visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    @Test fun keyboardOpensForDataInputAndClosesWhenReturningToMachines() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Machines").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.waitUntil(5_000) { !keyboardVisible() }
        val add = compose.onAllNodes(hasText("Add machine") or hasText("Add a machine", substring = true))
        add.onFirst().performClick()
        compose.onNodeWithText("Host").performClick().performTextInput("example.invalid")
        compose.waitUntil(5_000) { keyboardVisible() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Machines").assertIsDisplayed()
        compose.waitUntil(5_000) { !keyboardVisible() }
        // Inputs must still be usable on a second visit after the selection cleared focus.
        add.onFirst().performClick()
        compose.onNodeWithText("Host").performClick().performTextInput("second.invalid")
        compose.waitUntil(5_000) { keyboardVisible() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { !keyboardVisible() }
    }
}
