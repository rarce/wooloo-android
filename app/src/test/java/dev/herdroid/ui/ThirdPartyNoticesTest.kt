package dev.herdroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.WoolooMobileApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WoolooMobileApp::class)
class ThirdPartyNoticesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun originalLicenseCanBeSelectedAndReadFromBundledAssets() {
        var selected: Pair<String, String>? = null
        var closed = false
        compose.setContent {
            ThirdPartyNoticesDialog(
                onOpen = { title, path -> selected = title to path },
                onDismiss = { closed = true },
            )
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Bouncy Castle license"))
        compose.onNodeWithText("Bouncy Castle license").performClick()
        compose.runOnIdle {
            val (title, path) = selected!!
            assertEquals("Bouncy Castle license", title)
            val text = RuntimeEnvironment.getApplication().assets.open(path).bufferedReader().use { it.readText() }
            assertTrue(text.contains("The Legion of the Bouncy Castle"))
            assertTrue(text.contains("Permission is hereby granted"))
            assertTrue(text.contains("THE SOFTWARE IS PROVIDED"))
        }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
