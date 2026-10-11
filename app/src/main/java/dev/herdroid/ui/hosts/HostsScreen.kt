package dev.herdroid.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.components.SessionListRow
import dev.herdroid.ui.components.ListRowPosition
import dev.herdroid.ui.components.AgentStatusSummary
import dev.herdroid.ui.session.count

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    onOpenHost: (String) -> Unit,
    onAddHost: () -> Unit,
    onScanQr: () -> Unit,
    onEditHost: (String) -> Unit,
    onKeys: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = LocalContext.current.app
    val hosts by app.hosts.hosts.collectAsState()
    val connections by app.connections.connections.collectAsState()
    var pendingDelete by remember { mutableStateOf<HostProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = maxOf(64.dp, 48.dp * LocalDensity.current.fontScale),
                title = { Text("Machines") },
                actions = {
                    IconButton(onClick = onScanQr) { Icon(Icons.Outlined.QrCodeScanner, "Scan Wooloo QR") }
                    IconButton(onClick = onKeys) { Icon(Icons.Outlined.Key, "SSH keys") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (hosts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAddHost,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Add machine") },
                )
            }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Dns,
                title = "Add your first machine",
                body = "Scan the Remote Access QR in wooloo, or add a machine to see its spaces and agents.",
                modifier = Modifier.padding(padding),
            ) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onScanQr) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Text("  Scan Wooloo QR")
                }
                OutlinedButton(onClick = onAddHost) {
                    Icon(Icons.Outlined.Add, null)
                    Text("  Add a machine")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(hosts, key = { _, host -> host.id }) { index, host ->
                    HostCard(
                        host = host,
                        position = ListRowPosition.at(index, hosts.size),
                        connection = connections[host.id],
                        onOpen = { onOpenHost(host.id) },
                        onEdit = { onEditHost(host.id) },
                        onDisconnect = { app.connections.disconnect(host.id) },
                        onDelete = { pendingDelete = host },
                    )
                }
            }
        }
    }

    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${host.displayName}?") },
            text = { Text("This forgets the machine on this phone. Your herdr session and agents keep running.") },
            confirmButton = {
                TextButton(onClick = {
                    app.connections.disconnect(host.id)
                    app.hosts.delete(host.id)
                    app.secrets.remove(dev.herdroid.data.SecretStore.passwordKey(host.id))
                    app.secrets.remove(dev.herdroid.data.SecretStore.accessSecretKey(host.id))
                    pendingDelete = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HostCard(
    host: HostProfile,
    connection: HerdrConnection?,
    position: ListRowPosition,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = connection?.status?.collectAsState()?.value ?: ConnectionStatus.Idle
    val snapshot = connection?.snapshot?.collectAsState()?.value
    HostItem(host, status, snapshot, connection != null, onOpen, onEdit, onDisconnect, onDelete, position)
}

/** Machine connection state stays distinct from the agent states received from herdr. */
@Composable
internal fun HostItem(
    host: HostProfile,
    status: ConnectionStatus,
    snapshot: SessionSnapshot?,
    canDisconnect: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    position: ListRowPosition = ListRowPosition(),
) {
    var menu by remember { mutableStateOf(false) }
    val live = status is ConnectionStatus.Connected
    SessionListRow(
        onOpen = onOpen,
        onLongClick = { menu = true },
        actionLabel = "Machine actions",
        position = position,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Machine actions for ${host.displayName}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onEdit() })
                    if (canDisconnect) {
                        DropdownMenuItem(text = { Text("Disconnect") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                            onClick = { menu = false; onDisconnect() })
                    }
                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }
        },
    ) {
        Text(host.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(host.target + if (host.session != "default") " / ${host.session}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statusLine(status, host), style = MaterialTheme.typography.bodySmall,
                color = if (status is ConnectionStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            if (live && snapshot != null) {
                Text(count(snapshot.workspaces.size, "space"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        snapshot?.let {
            AgentStatusSummary(it.agents, Modifier.alpha(if (live) 1f else 0.45f))
            if (!live) {
                Text("Last known agent states", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun statusLine(status: ConnectionStatus, host: HostProfile): String = when (status) {
    is ConnectionStatus.Connecting -> status.step
    is ConnectionStatus.Reconnecting -> "Offline. Reconnecting: ${status.reason}"
    is ConnectionStatus.Failed -> status.message.lineSequence().first()
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Idle -> host.lastConnectedAt?.let { "Last connected ${relativeTime(it)}" }
        ?: "Tap to connect"
}

fun relativeTime(epochMs: Long): String {
    val delta = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        delta < 60 -> "just now"
        delta < 3600 -> "${delta / 60} min ago"
        delta < 86_400 -> "${delta / 3600} h ago"
        else -> "${delta / 86_400} d ago"
    }
}
