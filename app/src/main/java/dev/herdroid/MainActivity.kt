package dev.herdroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import dev.herdroid.ui.HerdroidRoot
import dev.herdroid.ui.theme.HerdroidTheme
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

class MainActivity : ComponentActivity() {
    private val openRequests = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val symbols by app.preferences.statusSymbols.collectAsState()
            CompositionLocalProvider(
                LocalStatusIndicators provides if (symbols) StatusIndicators.SYMBOLS else StatusIndicators.DOTS
            ) {
                HerdroidTheme {
                    HerdroidRoot(openRequests = openRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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
