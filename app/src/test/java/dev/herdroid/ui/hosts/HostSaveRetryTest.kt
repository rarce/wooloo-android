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

    @Test fun changedDestinationRequiresReviewAndCancelKeepsOriginalProfile() {
        val app = RuntimeEnvironment.getApplication() as WoolooMobileApp
        val original = dev.herdroid.data.HostProfile(id = "review-machine", hostname = "old.example",
            username = "alice", auth = dev.herdroid.data.AuthMethod.PASSWORD)
        app.hosts.upsert(original)
        var navigations = 0
        var connected = false
        compose.setContent {
            WoolooMobileTheme {
                HostEditScreen(original.id, onDone = { _, connect -> connected = connect; navigations++ }, onImportKey = {})
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Host", substring = false))
            .performTextReplacement("new.example")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.onNodeWithText("Trust changed connection?").assertExists()
        compose.runOnIdle {
            assertEquals(original, app.hosts.get(original.id))
            assertEquals(0, navigations)
        }
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle { assertEquals(original, app.hosts.get(original.id)) }
        // Cancelling grants no approval for a later edited destination.
        compose.onNode(hasSetTextAction() and hasText("Host", substring = false))
            .performTextReplacement("third.example")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.onNodeWithText("New: SSH alice@third.example").assertExists()
        compose.onNodeWithText("Trust & save", substring = false).performClick()
        compose.waitUntil(5000) { navigations == 1 }
        compose.runOnIdle {
            assertFalse(connected)
            assertEquals("third.example", app.hosts.get(original.id)!!.hostname)
            assertEquals(original.id, app.hosts.get(original.id)!!.id)
        }
    }

    @Test fun unchangedDestinationSavesWithoutTrustDialog() {
        val app = RuntimeEnvironment.getApplication() as WoolooMobileApp
        val original = dev.herdroid.data.HostProfile(id = "unchanged-machine", hostname = "old.example",
            username = "alice", auth = dev.herdroid.data.AuthMethod.PASSWORD)
        app.hosts.upsert(original)
        var navigations = 0
        compose.setContent {
            WoolooMobileTheme {
                HostEditScreen(original.id, onDone = { _, _ -> navigations++ }, onImportKey = {})
            }
        }
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(5000) { navigations == 1 }
        compose.onNodeWithText("Trust changed connection?").assertDoesNotExist()
    }


    @Test fun importedTunnelCannotConnectUntilDestinationAndTrustAreApproved() {
        val app = RuntimeEnvironment.getApplication() as WoolooMobileApp
        val original = dev.herdroid.data.HostProfile(id = "qr-machine", label = "Mac",
            hostname = "old.trycloudflare.com", transport = dev.herdroid.data.Transport.CLOUDFLARE,
            username = "alice", auth = dev.herdroid.data.AuthMethod.PASSWORD,
            accessClientId = "existing-client", hostKeyFingerprints = listOf("SHA256:old"))
        app.hosts.upsert(original)
        var navigations = 0
        val fingerprint = "SHA256:${"A".repeat(43)}"
        val link = "herdroid://add-host?transport=cloudflare&host=evil.example&label=Mac&user=alice&fp=$fingerprint"
        compose.setContent {
            WoolooMobileTheme {
                HostEditScreen(original.id, onDone = { _, _ -> navigations++ }, onImportKey = {}, link = link)
            }
        }
        compose.onAllNodesWithText("Connect", substring = false)[1].performClick()
        compose.onNodeWithText("Trust changed connection?").assertExists()
        compose.onNodeWithText("New: CLOUDFLARE alice@evil.example").assertExists()
        compose.onNodeWithText("New trusted SSH keys:\n$fingerprint").assertExists()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(0, navigations)
            assertEquals(original, app.hosts.get(original.id))
        }
    }

}
