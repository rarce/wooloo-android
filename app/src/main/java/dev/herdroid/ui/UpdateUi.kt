package dev.herdroid.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.herdroid.R
import dev.herdroid.update.AppUpdater
import dev.herdroid.update.UpdateRelease
import dev.herdroid.update.UpdateState

/** Settings rows: automatic checks, and one row that checks, installs or retries depending on state. */
@Composable
fun UpdateSettings(updater: AppUpdater) {
    if (!updater.enabled) {
        ListItem(
            headlineContent = { Text("Updates") },
            supportingContent = { Text("This build doesn't update itself. Release builds update from GitHub.") },
        )
        return
    }
    val state by updater.state.collectAsState()
    val autoCheck by updater.autoCheck.collectAsState()
    val lastCheckedAt by updater.lastCheckedAt.collectAsState()

    ListItem(
        headlineContent = { Text("Check for updates automatically") },
        supportingContent = { Text("Once a day, from GitHub releases") },
        trailingContent = { Switch(autoCheck, onCheckedChange = updater::setAutoCheck) },
    )
    val action: (() -> Unit)? = when (val s = state) {
        is UpdateState.Available -> { { updater.startInstall(s.release) } }
        is UpdateState.Failed -> s.release?.let { release -> { updater.startInstall(release) } } ?: updater::checkNow
        is UpdateState.Installing -> if (s.confirm != null) updater::continueInstall else null
        is UpdateState.Checking, is UpdateState.Downloading -> null
        UpdateState.Idle, UpdateState.UpToDate -> updater::checkNow
    }
    ListItem(
        modifier = if (action != null) Modifier.clickable(onClick = action) else Modifier,
        headlineContent = {
            Text(
                when (val s = state) {
                    is UpdateState.Available -> "Install ${s.release.versionName}"
                    is UpdateState.Failed -> if (s.release != null) "Retry update" else "Check for updates"
                    else -> "Check for updates"
                },
            )
        },
        supportingContent = {
            when (val s = state) {
                UpdateState.Idle -> Text(lastChecked(lastCheckedAt))
                UpdateState.Checking -> Text("Checking…")
                UpdateState.UpToDate -> Text("You're on the latest version")
                is UpdateState.Available -> Text("Version ${s.release.versionName} is available")
                is UpdateState.Downloading -> DownloadProgress(s)
                is UpdateState.Installing ->
                    Text(if (s.confirm != null) "Tap to finish installing ${s.release.versionName}" else "Installing…")
                is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** Offers a release found by an automatic check; stays up with progress once the user installs it. */
@Composable
fun UpdatePrompt(updater: AppUpdater) {
    val release = updater.prompt.collectAsState().value ?: return
    val state by updater.state.collectAsState()
    val appName = LocalContext.current.getString(R.string.app_name)
    AlertDialog(
        onDismissRequest = updater::dismissPrompt,
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$appName ${release.versionName} is ready to install.")
                when (val s = state) {
                    is UpdateState.Downloading -> DownloadProgress(s)
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    else -> ReleaseNotes(release)
                }
            }
        },
        confirmButton = {
            val working = state is UpdateState.Downloading || state is UpdateState.Installing
            Button(enabled = !working, onClick = { updater.startInstall(release) }) {
                Text(if (state is UpdateState.Failed) "Retry" else "Install")
            }
        },
        dismissButton = { TextButton(onClick = updater::dismissPrompt) { Text("Later") } },
    )
}

@Composable
private fun ReleaseNotes(release: UpdateRelease) {
    val notes = release.notes.trim()
    if (notes.isEmpty()) return
    Text(
        notes,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun DownloadProgress(state: UpdateState.Downloading) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Downloading ${state.release.versionName}… ${(state.progress * 100).toInt()}%")
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
}

private fun lastChecked(at: Long): String =
    if (at == 0L) "Not checked yet"
    else "Last checked ${DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
