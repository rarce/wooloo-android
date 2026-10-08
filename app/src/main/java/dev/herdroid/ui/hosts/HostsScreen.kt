package dev.herdroid.ui.hosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.R
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.session.MiniatureRow
import dev.herdroid.ui.session.TabMiniature
import dev.herdroid.ui.session.count
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono

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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDelete by remember { mutableStateOf<HostProfile?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Your herds") },
                actions = {
                    IconButton(onClick = onScanQr) { Icon(Icons.Outlined.QrCodeScanner, "Scan Wooloo QR") }
                    IconButton(onClick = onKeys) { Icon(Icons.Outlined.Key, "SSH keys") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                scrollBehavior = scrollBehavior,
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
                title = "Connect to your herd",
                body = "${stringResource(R.string.app_name)} connects over SSH to a machine running herdr, so you can watch " +
                    "your agents, answer them and jump into any pane from your phone.",
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(hosts, key = { it.id }) { host ->
                    HostCard(
                        host = host,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: HostProfile,
    connection: HerdrConnection?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val status = connection?.status?.collectAsState()?.value ?: ConnectionStatus.Idle
    val snapshot = connection?.snapshot?.collectAsState()?.value
    val statusColors = LocalStatusColors.current
    val live = status is ConnectionStatus.Connected

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onLongClickLabel = "Machine actions", onLongClick = { menu = true }, onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (connection == null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (connection == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        host.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        host.target + if (host.session != "default") " ${host.session}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, "Machine actions for ${host.displayName}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                        if (connection != null) {
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                                onClick = { menu = false; onDisconnect() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            // The whole herd at a glance; dimmed while it's only the last known state.
            if (snapshot != null && snapshot.workspaces.isNotEmpty()) {
                Row(Modifier.alpha(if (live) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    snapshot.workspaces.take(5).forEach { workspace ->
                        snapshot.activeTabId(workspace)?.let { TabMiniature(snapshot, it, MiniatureRow) }
                    }
                }
            }
            if (live && snapshot != null) {
                val agents = snapshot.agents
                val needing = agents.count { it.agentStatus == AgentStatus.BLOCKED }
                val done = agents.count { it.agentStatus == AgentStatus.DONE }
                val working = agents.count { it.agentStatus == AgentStatus.WORKING }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (needing > 0) Summary(if (needing == 1) "1 needs you" else "$needing need you", statusColors.blocked)
                    if (done > 0) Summary("$done done", statusColors.done)
                    if (working > 0) Summary("$working working", MaterialTheme.colorScheme.onSurfaceVariant)
                    if (needing + done + working == 0) {
                        Summary(
                            "${count(snapshot.workspaces.size, "workspace")}, no agents busy",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    statusLine(status, host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status is ConnectionStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Summary(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = color)
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
