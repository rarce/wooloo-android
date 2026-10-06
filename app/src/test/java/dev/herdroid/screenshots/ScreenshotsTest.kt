package dev.herdroid.screenshots

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.FailureKind
import dev.herdroid.terminal.ModifierState
import dev.herdroid.terminal.TerminalModel
import dev.herdroid.terminal.TerminalView
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.session.SessionContent
import dev.herdroid.ui.terminal.ExtraKeysBar
import dev.herdroid.ui.terminal.PaneSwitcher
import dev.herdroid.ui.terminal.TerminalTopBar
import dev.herdroid.ui.theme.HerdroidTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders key screens with sample data on the JVM (no device needed), via Robolectric's
 * native graphics. Opt-in because it downloads an Android runtime the first time:
 *
 *   ./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest*'
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-xxhdpi", application = android.app.Application::class)
class ScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = System.getProperty("herdroid.screenshotDir") ?: "build/screenshots"

    @Before
    fun enabled() {
        assumeTrue(System.getProperty("herdroid.screenshots") == "true")
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("$outDir/$name.png")
    }

    private val snapshot = SampleData.snapshot

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        HerdroidTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    @Test
    fun sessionOverview() = capture("session") {
        Frame {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("devbox")
                                Text(
                                    "default · 3 workspaces · herdr 0.9.3",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = { IconButton({}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
                        actions = {
                            IconButton({}) { Icon(Icons.Outlined.DashboardCustomize, null) }
                            IconButton({}) { Icon(Icons.Outlined.MoreVert, null) }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    SessionContent(
                        snapshot = snapshot,
                        stale = false,
                        onOpenPane = {},
                        onPaneMenu = {},
                        onCompose = {},
                        onWorkspaceAction = {},
                        onSplit = { _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun reconnectingBanner() = capture("banners") {
        Frame {
            Column {
                ConnectionBanner(
                    ConnectionStatus.Reconnecting(3, "Timed out reaching devbox.", System.currentTimeMillis() + 8_000),
                    onRetry = {}, onEditHost = {},
                )
                ConnectionBanner(
                    ConnectionStatus.Failed(FailureKind.AUTH, "Authentication failed for herd@devbox. Is the public key in ~/.ssh/authorized_keys?"),
                    onRetry = {}, onEditHost = {},
                )
            }
        }
    }

    @Test
    fun agentTerminal() {
        val model = TerminalModel()
        model.applyFrame(62, 24, true, SampleData.agentScreen.toByteArray())
        val pane = snapshot.pane("w1:p1")!!
        val agent = snapshot.agentFor("w1:p1")!!
        capture("terminal") {
            Frame {
                Column(Modifier.fillMaxSize()) {
                    TerminalTopBar(
                        pane = pane, snapshot = snapshot,
                        agentName = agent.displayName, agentStatus = agent.agentStatus, agentLabel = agent.statusLabel(),
                        onBack = {}, menu = false, onMenu = {}, readOnly = false, onToggleReadOnly = {},
                        onFont = {}, onCopy = {}, onZoom = {}, onSplit = {}, onRename = {}, onClosePane = {}, onReconnect = {},
                    )
                    PaneSwitcher(snapshot, pane) {}
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        factory = { ctx ->
                            TerminalView(ctx, model, ModifierState()).apply {
                                setPadding(8, 4, 8, 4)
                                setFontSize(11.5f, notify = false)
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }
                        },
                    )
                    ExtraKeysBar(
                        modifiers = ModifierState().apply { ctrl = true },
                        haptics = false,
                        onSpecial = {}, onText = {}, onPaste = {}, onToggleKeyboard = {}, onComposer = {},
                        composerHighlighted = true,
                    )
                }
            }
        }
    }
}
