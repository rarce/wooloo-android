package dev.herdroid.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.SnapshotHealth
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.ui.components.SnapshotBanner
import dev.herdroid.ui.hosts.HostItem
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
class SnapshotBannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSnapshotOffersRefreshAndSuccessfulRefreshRemovesWarning() {
        val health = mutableStateOf(SnapshotHealth(error = "Refresh failed"))
        var refreshes = 0
        compose.setContent { WoolooMobileTheme { SnapshotBanner(health.value) { refreshes++ } } }
        compose.onNodeWithText("Session data may be outdated").assertExists()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            health.value = health.value.copy(refreshing = true)
        }
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Session data may be outdated").assertExists()
        compose.runOnIdle { health.value = SnapshotHealth(stale = false) }
        compose.onNodeWithText("Session data may be outdated").assertDoesNotExist()
    }

    @Test fun staleSnapshotIsLabelledEvenWhenSshIsConnected() {
        compose.setContent {
            WoolooMobileTheme {
                HostItem(HostProfile(hostname = "example.invalid", username = "alice"),
                    ConnectionStatus.Connected, SessionSnapshot(), true,
                    onOpen = {}, onEdit = {}, onDisconnect = {}, onDelete = {}, snapshotStale = true)
            }
        }
        compose.onNodeWithText("Connected · session data outdated").assertExists()
        compose.onNodeWithText("Last known agent states").assertExists()
    }
}
