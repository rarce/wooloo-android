package dev.herdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import dev.herdroid.data.HostLink
import dev.herdroid.ui.WoolooMobileRoot
import dev.herdroid.ui.theme.WoolooMobileTheme
import dev.herdroid.ui.theme.LocalStatusIndicators
import dev.herdroid.ui.theme.StatusIndicators
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Deep link payload from a notification tap. */
data class OpenRequest(
    val hostId: String,
    val paneId: String?,
    val workspaceId: String? = null,
    val nonce: Long = System.nanoTime(),
)

/** A `herdroid://add-host` link to open in the host editor (never connects by itself). */
data class HostLinkRequest(val link: String, val nonce: Long = System.nanoTime())

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<OpenRequest?>(null)
    private val hostLinks = MutableStateFlow<HostLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val symbols by app.preferences.statusSymbols.collectAsState()
            CompositionLocalProvider(
                LocalStatusIndicators provides if (symbols) StatusIndicators.SYMBOLS else StatusIndicators.DOTS
            ) {
                WoolooMobileTheme {
                    WoolooMobileRoot(openRequests = openRequests, hostLinks = hostLinks)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.updater.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == HostLink.SCHEME) {
            hostLinks.value = HostLinkRequest(data.toString())
            // Handled once: don't reopen the editor when the activity is recreated.
            intent.data = null
            return
        }
        val hostId = intent?.getStringExtra(EXTRA_HOST_ID) ?: return
        openRequests.value = OpenRequest(hostId, intent.getStringExtra(EXTRA_PANE_ID), intent.getStringExtra(EXTRA_WORKSPACE_ID))
        intent.removeExtra(EXTRA_HOST_ID)
    }

    companion object {
        const val EXTRA_HOST_ID = "host_id"
        const val EXTRA_PANE_ID = "pane_id"
        const val EXTRA_WORKSPACE_ID = "workspace_id"
    }
}
