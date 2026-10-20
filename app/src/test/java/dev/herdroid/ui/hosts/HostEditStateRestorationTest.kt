package dev.herdroid.ui.hosts

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.WoolooMobileApp
import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostProfile
import dev.herdroid.data.SecretStore
import dev.herdroid.data.Transport
import dev.herdroid.ui.theme.WoolooMobileTheme
import javax.crypto.KeyGenerator
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
class HostEditStateRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restorationDropsCredentialDraftsAndPendingApprovalButKeepsOtherEditsAndSavedSecrets() {
        val app = RuntimeEnvironment.getApplication() as WoolooMobileApp
        val original = HostProfile(id = "restore-machine", hostname = "old.example", username = "alice",
            transport = Transport.CLOUDFLARE, auth = AuthMethod.PASSWORD, accessClientId = "client")
        app.hosts.upsert(original)
        // Real encrypted entries in the same preferences used by the app. Robolectric lacks
        // Android Keystore; the fixture supplies an AES key without changing production wiring.
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val prefs = app.getSharedPreferences("herdroid_secrets", Context.MODE_PRIVATE)
        val vault = SecretStore(prefs) { key }
        vault.put(SecretStore.passwordKey(original.id), "saved password")
        vault.put(SecretStore.accessSecretKey(original.id), "saved token")
        val encryptedBefore = prefs.all.toMap()
        var navigations = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            WoolooMobileTheme {
                HostEditScreen(original.id, onDone = { _, _ -> navigations++ }, onImportKey = {})
            }
        }
        compose.waitForIdle()
        field("Tunnel hostname").performTextReplacement("new.example")
        field("User").performTextReplacement("bob")
        field("Name (optional)").performTextReplacement("Edited machine")
        field("Session").performScrollTo().performTextReplacement("edited-session")
        field("Password (saved — leave empty to keep)").performScrollTo().performTextInput("draft password")
        field("Client secret (saved — leave empty to keep)").performScrollTo().performTextInput("draft token")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.onNodeWithText("Trust changed connection?").assertExists()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Trust changed connection?").assertDoesNotExist()
        field("Password (saved — leave empty to keep)").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field("Client secret (saved — leave empty to keep)").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field("Tunnel hostname").assertTextContains("new.example")
        field("User").assertTextContains("bob")
        field("Name (optional)").assertTextContains("Edited machine")
        field("Session").assertTextContains("edited-session")
        compose.runOnIdle {
            assertEquals(0, navigations)
            assertEquals(original, app.hosts.get(original.id))
            assertEquals(encryptedBefore, prefs.all)
        }
        // Restoring grants no approval: saving requires a fresh review, now with empty drafts.
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.onNodeWithText("Trust changed connection?").assertExists()
        compose.onNodeWithText("Trust & save", substring = false).performClick()
        compose.waitUntil(5000) { navigations == 1 }
        compose.runOnIdle {
            assertEquals("new.example", app.hosts.get(original.id)!!.hostname)
            assertEquals(encryptedBefore, prefs.all)
            assertEquals("saved password", vault.get(SecretStore.passwordKey(original.id)))
            assertEquals("saved token", vault.get(SecretStore.accessSecretKey(original.id)))
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = false))
}
