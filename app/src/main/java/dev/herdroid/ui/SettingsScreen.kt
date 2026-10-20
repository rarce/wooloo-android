package dev.herdroid.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.herdroid.BuildConfig
import dev.herdroid.R
import dev.herdroid.app
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.terminal.TerminalView
import dev.herdroid.ui.components.StatusDot
import dev.herdroid.ui.theme.LocalStatusIndicators
import dev.herdroid.ui.theme.StatusIndicators
import dev.herdroid.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var document by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notices by remember { mutableStateOf(false) }
    if (notices && document == null) {
        ThirdPartyNoticesDialog(
            onOpen = { title, path -> document = title to path },
            onDismiss = { notices = false },
        )
    }
    document?.let { (title, path) ->
        val text = remember(path) { context.assets.open(path).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { document = null },
            title = { Text(title) },
            text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { document = null }) { Text("Close") } },
        )
    }
    val prefs = context.app.preferences
    val fontSize by prefs.fontSize.collectAsState()
    val keepScreenOn by prefs.keepScreenOn.collectAsState()
    val haptics by prefs.haptics.collectAsState()
    val statusSymbols by prefs.statusSymbols.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Terminal text size") },
                supportingContent = {
                    Column {
                        Text("${"%.1f".format(fontSize)} sp — pinch the terminal to adjust too")
                        Slider(
                            value = fontSize,
                            onValueChange = { prefs.setFontSize(it) },
                            valueRange = TerminalView.MIN_FONT_SP..TerminalView.MAX_FONT_SP,
                        )
                        Text(
                            "┌─ wooloo ─┐ agents: 3 ✓",
                            fontFamily = Mono,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * (fontSize / 12f),
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Keep screen on in terminal") },
                supportingContent = { Text("Handy while watching an agent work") },
                trailingContent = { Switch(keepScreenOn, onCheckedChange = prefs::setKeepScreenOn) },
            )
            ListItem(
                headlineContent = { Text("Distinct status symbols") },
                supportingContent = {
                    Column {
                        Text("Use a different shape for each state, alongside its color")
                        CompositionLocalProvider(
                            LocalStatusIndicators provides if (statusSymbols) StatusIndicators.SYMBOLS else StatusIndicators.DOTS
                        ) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                AgentStatus.entries.forEach { status ->
                                    StatusDot(status, size = 9.dp)
                                    Text(status.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        }
                    }
                },
                trailingContent = { Switch(statusSymbols, onCheckedChange = prefs::setStatusSymbols) },
            )
            ListItem(
                headlineContent = { Text("Key haptics") },
                trailingContent = { Switch(haptics, onCheckedChange = prefs::setHaptics) },
            )
            ListItem(
                headlineContent = { Text("Agent quotas") },
                supportingContent = {
                    Text("Enable quotas on each machine's spaces screen. Permission applies separately to each remote sign-in.")
                },
            )
            ListItem(
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Enable or disable agent alerts in Android settings") },
            )
            UpdateSettings(context.app.updater)
            listOf(
                "Privacy" to "legal/privacy.txt",
                "App license (MIT)" to "legal/LICENSE.txt",
                "Atkinson font license" to "licenses/atkinson-hyperlegible-OFL.txt",
                "Third-party inventory" to "legal/dependencies.txt",
            ).forEach { (title, path) ->
                ListItem(
                    modifier = Modifier.clickable { document = title to path },
                    headlineContent = { Text(title) },
                    supportingContent = { Text("Read offline") },
                )
            }
            ListItem(
                modifier = Modifier.clickable { notices = true },
                headlineContent = { Text("Third-party notices") },
                supportingContent = { Text("Original licenses and attributions, available offline") },
            )

            ListItem(
                headlineContent = { Text("About") },
                supportingContent = {
                    Text(
                        "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}\n" +
                            "Watch your agents, reply to them and open their terminals over SSH.",
                    )
                },
            )
        }
    }
}
