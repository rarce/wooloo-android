package dev.herdroid.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.WorkspaceInfo
import dev.herdroid.ui.components.SnapshotBanner
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.components.StatusLabel
import dev.herdroid.ui.components.SessionListRow
import dev.herdroid.ui.components.ListRowPosition

/**
 * A machine's herdr session: its spaces (herdr workspaces), in the order reported by herdr. Spaces are
 * the top of the hierarchy, as in herdr's sidebar; agents live inside them ([SpaceScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    hostId: String,
    onBack: () -> Unit,
    onOpenSpace: (String) -> Unit,
    onEditHost: () -> Unit,
) {
    val app = LocalContext.current.app
    val connection = remember(hostId) { app.connections.connect(hostId) }
    if (connection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val status by connection.status.collectAsState()
    val snapshot by connection.snapshot.collectAsState()
    val snapshotHealth by connection.snapshotHealth.collectAsState()
    val prompt by connection.prompt.collectAsState()
    val ui = rememberSessionUi(connection)
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = maxOf(64.dp, 48.dp * LocalDensity.current.fontScale),
                title = {
                    Column {
                        Text(connection.profile.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subtitle(status, snapshot, connection),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { ui.dialog = SessionDialog.NewSpace }, enabled = status == ConnectionStatus.Connected) {
                        Icon(Icons.Outlined.DashboardCustomize, "New space")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit machine") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { menu = false; onEditHost() },
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                                onClick = {
                                    menu = false
                                    app.connections.disconnect(hostId)
                                    onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBanner(status, onRetry = { connection.retryNow() }, onEditHost = onEditHost)
            if (status == ConnectionStatus.Connected) {
                SnapshotBanner(snapshotHealth, onRetry = { connection.requestRefresh() })
            }
            RefreshBox(connection) {
                val snap = snapshot
                when {
                    snap == null -> LoadingOrEmpty(status)
                    snap.workspaces.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Workspaces,
                        title = "No spaces yet",
                        body = "This herdr session is empty. Create a space to start a shell or an agent.",
                    ) {
                        FilledTonalButton(onClick = { ui.dialog = SessionDialog.NewSpace }) { Text("New space") }
                    }
                    else -> SpacesContent(
                        snapshot = snap,
                        stale = status !is ConnectionStatus.Connected || snapshotHealth.stale,
                        onOpenSpace = onOpenSpace,
                        onSpaceAction = { ui.dialog = it },
                        header = {
                            Column {
                                HostStatsCard(connection)
                                AgentQuotaCard(connection)
                            }
                        },
                    )
                }
            }
        }
    }

    ConnectionPrompts(connection, prompt)
    SessionOverlays(ui, connection, onOpenPane = {})
}

/** Pull to re-read the snapshot from herdr. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshBox(connection: HerdrConnection, content: @Composable () -> Unit) {
    val health by connection.snapshotHealth.collectAsState()
    PullToRefreshBox(
        isRefreshing = health.refreshing,
        onRefresh = { connection.requestRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}

private fun subtitle(status: ConnectionStatus, snapshot: SessionSnapshot?, connection: HerdrConnection): String =
    when (status) {
        ConnectionStatus.Connected -> buildString {
            append("Session ").append(connection.profile.session)
            snapshot?.let { if (it.version.isNotBlank()) append(", herdr ").append(it.version) }
        }
        is ConnectionStatus.Connecting -> "Connecting…"
        is ConnectionStatus.Reconnecting -> "Offline, showing the last known state"
        is ConnectionStatus.Failed -> "Not connected"
        ConnectionStatus.Idle -> ""
    }

@Composable
internal fun LoadingOrEmpty(status: ConnectionStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (status is ConnectionStatus.Connecting || status is ConnectionStatus.Reconnecting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    (status as? ConnectionStatus.Connecting)?.step ?: "Waiting for the machine…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SpacesContent(
    snapshot: SessionSnapshot,
    stale: Boolean,
    onOpenSpace: (String) -> Unit,
    onSpaceAction: (SessionDialog) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().alpha(if (stale) 0.6f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        header?.let { item(key = "host") { it() } }
        item(key = "title") { SectionLabel("Spaces", snapshot.workspaces.size) }
        itemsIndexed(snapshot.workspaces, key = { _, it -> "ws-" + it.workspaceId }) { index, workspace ->
            SpaceCard(workspace, snapshot, ListRowPosition.at(index, snapshot.workspaces.size), onOpen = { onOpenSpace(workspace.workspaceId) }, onAction = onSpaceAction)
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** The workspace's server state and order are authoritative, including unknown/idle. */
@Composable
private fun SpaceCard(
    workspace: WorkspaceInfo,
    snapshot: SessionSnapshot,
    position: ListRowPosition,
    onOpen: () -> Unit,
    onAction: (SessionDialog) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SessionListRow(
        onOpen = onOpen,
        onLongClick = { menu = true },
        actionLabel = "Space actions",
        position = position,
        leading = {
            Box(Modifier.size(44.dp, 30.dp), contentAlignment = Alignment.Center) {
                val tabId = snapshot.activeTabId(workspace)
                if (tabId != null) {
                    TabMiniature(snapshot, tabId, Modifier.fillMaxSize()
                        .semantics { contentDescription = "Active tab layout of ${workspace.label}" })
                } else {
                    Icon(Icons.Outlined.Workspaces, "Tab layout unavailable", tint = MaterialTheme.colorScheme.outline)
                }
            }
        },
        focused = workspace.focused,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "Actions for ${workspace.label.ifBlank { "Space ${workspace.number}" }}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SpaceMenuItems(workspace, onAction) { menu = false }
                }
            }
        },
    ) {
        Text(workspace.label.ifBlank { "Space ${workspace.number}" },
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusLabel(workspace.agentStatus)
            Text("${count(workspace.tabCount, "tab")}, ${count(workspace.paneCount, "pane")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** New tab, rename and close: the same actions from the spaces list and from inside a space. */
@Composable
internal fun SpaceMenuItems(workspace: WorkspaceInfo, onAction: (SessionDialog) -> Unit, onDismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text("New tab") },
        leadingIcon = { Icon(Icons.Outlined.Add, null) },
        onClick = { onDismiss(); onAction(SessionDialog.NewTab(workspace)) },
    )
    DropdownMenuItem(
        text = { Text("Rename space") },
        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
        onClick = { onDismiss(); onAction(SessionDialog.RenameSpace(workspace)) },
    )
    DropdownMenuItem(
        text = { Text("Close space") },
        leadingIcon = { Icon(Icons.Outlined.Close, null) },
        onClick = { onDismiss(); onAction(SessionDialog.CloseSpace(workspace)) },
    )
}

internal fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * "workspace › tab › extra", skipping parts that add nothing: the tab when it has herdr's default
 * numeric label and is the workspace's only tab, and repeats (a cwd named like its workspace).
 */
internal fun breadcrumb(workspace: WorkspaceInfo?, tab: TabInfo?, extra: String? = null): String =
    listOfNotNull(
        workspace?.label?.takeIf { it.isNotBlank() },
        tab?.takeIf { it.hasCustomLabel || (workspace?.tabCount ?: 0) > 1 }?.displayLabel,
        extra?.takeIf { it.isNotBlank() },
    ).distinct().joinToString(" › ")

@Composable
internal fun SectionLabel(text: String, count: Int) {
    Row(
        Modifier.padding(top = 4.dp, bottom = 10.dp, start = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
