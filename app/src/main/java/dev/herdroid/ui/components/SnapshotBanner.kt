package dev.herdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herdroid.R
import dev.herdroid.connection.SnapshotHealth

/** A failed snapshot does not imply the terminal transport has stopped working. */
@Composable
fun SnapshotBanner(health: SnapshotHealth, onRetry: () -> Unit) {
    if (health.error == null) return
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.snapshot_stale_title), color = colors.onTertiaryContainer,
                style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.snapshot_stale_body), color = colors.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall)
        }
        if (health.refreshing) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.snapshot_retry)) }
        }
    }
}
