package dev.herdroid.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.FailureKind
import kotlinx.coroutines.delay

/** Slim, non-blocking banner describing connection health with the one action that helps. */
@Composable
fun ConnectionBanner(
    status: ConnectionStatus,
    onRetry: () -> Unit,
    onEditHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = status !is ConnectionStatus.Connected && status !is ConnectionStatus.Idle,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        val colors = MaterialTheme.colorScheme
        val (background, foreground) = when (status) {
            is ConnectionStatus.Failed -> colors.errorContainer to colors.onErrorContainer
            is ConnectionStatus.Reconnecting -> colors.tertiaryContainer to colors.onTertiaryContainer
            else -> colors.surfaceContainerHigh to colors.onSurface
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                is ConnectionStatus.Connecting -> {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = foreground)
                    Text(status.step, color = foreground, style = MaterialTheme.typography.bodyMedium)
                }
                is ConnectionStatus.Reconnecting -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(status) {
                        while (true) { now = System.currentTimeMillis(); delay(250) }
                    }
                    val seconds = ((status.retryAtMs - now) / 1000).coerceAtLeast(0)
                    Icon(Icons.Outlined.CloudOff, null, tint = foreground)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (seconds > 0) "Offline · retrying in ${seconds}s" else "Reconnecting…",
                            color = foreground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            status.reason,
                            color = foreground.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    TextButton(onClick = onRetry) { Text("Retry now", color = foreground) }
                }
                is ConnectionStatus.Failed -> {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = foreground)
                    Column(Modifier.weight(1f)) {
                        Text(failureTitle(status.kind), color = foreground, style = MaterialTheme.typography.labelLarge)
                        Text(
                            status.message,
                            color = foreground.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = onRetry) { Text("Retry", color = foreground) }
                        if (status.kind in setOf(FailureKind.SECURE_STORAGE, FailureKind.AUTH, FailureKind.HERDR_MISSING)) {
                            TextButton(onClick = onEditHost) { Text("Edit host", color = foreground) }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

fun failureTitle(kind: FailureKind) = when (kind) {
    FailureKind.SECURE_STORAGE -> "Saved credentials unavailable"
    FailureKind.AUTH -> "Authentication failed"
    FailureKind.HOST_KEY -> "Host key not trusted"
    FailureKind.HERDR_MISSING -> "herdr not found"
    FailureKind.SERVER_DOWN -> "herdr server not running"
    FailureKind.OUTDATED -> "herdr needs an update"
    FailureKind.NETWORK -> "Can't reach the machine"
    FailureKind.OTHER -> "Connection failed"
}
