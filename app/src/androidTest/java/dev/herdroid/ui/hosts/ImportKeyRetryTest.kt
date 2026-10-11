package dev.herdroid.ui.hosts

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class ImportKeyRetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureKeepsInputsAndOnlyOneImportCanRunAtOnce() {
        val release = CountDownLatch(1)
        val attempts = CopyOnWriteArrayList<List<String?>>()
        var dismissed = 0
        compose.setContent {
            WoolooMobileTheme {
                ImportKeyForm(onDismiss = { dismissed++ }, importKey = { name, pem, passphrase ->
                    attempts += listOf(name, pem, passphrase)
                    if (attempts.size == 1) {
                        check(release.await(10, TimeUnit.SECONDS))
                        throw IOException("private key parser diagnostic")
                    }
                })
            }
        }
        try {
            compose.onNodeWithText("Name").performTextInput("my key")
            compose.onNodeWithText("Private key").performTextInput("private key draft")
            compose.onNodeWithText("Passphrase (if any)").performTextInput("key phrase")
            compose.onNodeWithText("Import").performClick()
            compose.waitUntil(5000) { attempts.size == 1 }
            compose.onNodeWithText("Import").assertIsNotEnabled()
            compose.onNodeWithText("Cancel").assertIsNotEnabled()
            release.countDown()
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Could not import that key. Check the key and passphrase, then try again.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("private key draft").assertExists()
            compose.runOnIdle { assertEquals(0, dismissed) }
            compose.onNodeWithText("Import").performClick()
            compose.waitUntil(5000) { dismissed == 1 }
            assertEquals(listOf(listOf("my key", "private key draft", "key phrase"), listOf("my key", "private key draft", "key phrase")), attempts)
        } finally { release.countDown() }
    }
}
