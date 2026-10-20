package dev.herdroid.ui.hosts

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.herdroid.data.HostLink
import dev.herdroid.data.SshKeyInfo
import dev.herdroid.data.Transport
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class RemoteAccessSetupTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val camera = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun copiesThePublicKeyWithoutAShellCommand() {
        val key = SshKeyInfo(
            id = "setup-test", name = "Phone key", type = "Ed25519",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample phone-key", createdAt = 0,
        )
        compose.setContent { WoolooMobileTheme { PublicKeyCard(key) } }
        compose.onNodeWithText(key.publicKey).assertExists()
        compose.onNodeWithText("Copy public key", substring = true).performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(key.publicKey, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun pastedDirectSshLinkPreservesTheSelectedRemoteDestination() {
        val accepted = AtomicReference<String?>()
        val raw = "herdroid://add-host?transport=ssh&host=devbox.example&port=2222&user=dev&session=mobile-test&herdr=%2Fopt%2Fbin%2Fherdr"
        compose.setContent { WoolooMobileTheme { ScanQrScreen(onBack = {}, onScanned = accepted::set) } }
        // On a small screen the camera box pushes the button below the fold.
        compose.onNodeWithText("Paste link").performScrollTo().performClick()
        compose.onNodeWithText("Link from Remote Access").performTextInput(raw)
        compose.onNodeWithText("Continue").performClick()
        compose.runOnIdle {
            assertEquals(raw, accepted.get())
            val parsed = requireNotNull(HostLink.parse(requireNotNull(accepted.get())))
            assertEquals(Transport.SSH, parsed.transport)
            assertEquals("devbox.example", parsed.hostname)
            assertEquals(2222, parsed.port)
            assertEquals("dev", parsed.username)
            assertEquals("mobile-test", parsed.session)
            assertEquals("/opt/bin/herdr", parsed.herdrPath)
        }
    }
}
