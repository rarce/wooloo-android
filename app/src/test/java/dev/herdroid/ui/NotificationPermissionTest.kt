package dev.herdroid.ui

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.herdroid.MainActivity
import dev.herdroid.WoolooMobileApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WoolooMobileApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchAndRecreationNeverRequestPermissionButSettingsRemainsExplicit() {
        compose.onNodeWithText("Machines").assertIsDisplayed()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).lastRequestedPermission) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Machines").assertIsDisplayed()
        compose.runOnIdle { assertNull(shadowOf(compose.activity).lastRequestedPermission) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Notifications").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
            assertEquals("dev.herdroid", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            assertNull(shadowOf(compose.activity).lastRequestedPermission)
        }
    }
}
