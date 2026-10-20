package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.herdroid.WoolooMobileApp
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h851dp", application = WoolooMobileApp::class)
class SettingsPrivacyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legalDocumentsCanBeReadAndDismissedOffline() {
        compose.setContent { WoolooMobileTheme { SettingsScreen(onBack = {}) } }
        compose.onNodeWithText("Privacy").performScrollTo()
        if (System.getProperty("herdroid.screenshots") == "true") {
            compose.onRoot().captureRoboImage("build/screenshots/settings-privacy.png")
        }
        compose.onNodeWithText("Privacy").performClick()
        compose.onNodeWithText("Optional agent quotas (experimental)", substring = true).assertExists()
        if (System.getProperty("herdroid.screenshots") == "true") {
            compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/privacy-dialog.png")
        }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("App license (MIT)").performScrollTo().performClick()
        compose.onNodeWithText("Permission is hereby granted", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Atkinson font license").performScrollTo().performClick()
        compose.onNodeWithText("SIL OPEN FONT LICENSE", substring = true).assertExists()
        compose.onNodeWithText("Close").performClick()
    }
}
