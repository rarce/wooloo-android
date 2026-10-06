package dev.herdroid.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.herdroid.BuildConfig
import dev.herdroid.app
import dev.herdroid.terminal.TerminalView
import dev.herdroid.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.app.preferences
    val fontSize by prefs.fontSize.collectAsState()
    val keepScreenOn by prefs.keepScreenOn.collectAsState()
    val haptics by prefs.haptics.collectAsState()

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
                            "┌─ herd ─┐ agents: 3 ✓",
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
                headlineContent = { Text("Key haptics") },
                trailingContent = { Switch(haptics, onCheckedChange = prefs::setHaptics) },
            )
            ListItem(
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Choose how agent alerts reach you") },
            )
            ListItem(
                headlineContent = { Text("About") },
                supportingContent = {
                    Text(
                        "Herdroid ${BuildConfig.VERSION_NAME}\n" +
                            "Speaks herdr's socket API through `herdr remote-api-bridge` and streams panes with " +
                            "`herdr terminal session control`, all over one SSH connection.",
                    )
                },
            )
        }
    }
}
