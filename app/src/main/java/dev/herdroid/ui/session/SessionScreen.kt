package dev.herdroid.ui.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.WorkspaceInfo
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/**
 * A machine's herdr session: its spaces (herdr workspaces), whoever needs you first. Spaces are
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
    val prompt by connection.prompt.collectAsState()
    val ui = rememberSessionUi(connection)
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
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
                    IconButton(onClick = { ui.dialog = SessionDialog.NewSpace }, enabled = connection.isLive) {
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
                        stale = status !is ConnectionStatus.Connected,
                        onOpenSpace = onOpenSpace,
                        onSpaceAction = { ui.dialog = it },
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
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                connection.action { snapshot() }
                refreshing = false
            }
        },
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
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().alpha(if (stale) 0.6f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") { SectionLabel("Spaces", snapshot.workspaces.size) }
        items(snapshot.spacesByAttention, key = { "ws-" + it.workspaceId }) { workspace ->
            SpaceCard(workspace, snapshot, onOpen = { onOpenSpace(workspace.workspaceId) }, onAction = onSpaceAction)
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** One space: its active tab's miniature, its name, and who in it needs you. Tap to go in. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceCard(
    workspace: WorkspaceInfo,
    snapshot: SessionSnapshot,
    onOpen: () -> Unit,
    onAction: (SessionDialog) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val status = LocalStatusColors.current
    val agents = snapshot.agentsIn(workspace.workspaceId)
    val needing = agents.filter { status.isSolid(it.agentStatus) }
    val working = agents.count { it.agentStatus == AgentStatus.WORKING }
    val (line, lineColor) = when {
        needing.isNotEmpty() -> {
            val first = needing.first()
            val verb = if (first.agentStatus == AgentStatus.BLOCKED) "needs you" else "is done"
            val more = if (needing.size > 1) ", ${needing.size - 1} more" else ""
            "${first.displayName} $verb$more" to status.of(first.agentStatus)
        }
        working > 0 -> "${count(working, "agent")} working" to MaterialTheme.colorScheme.onSurfaceVariant
        agents.isNotEmpty() -> "${count(agents.size, "agent")}, all quiet" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "${count(workspace.tabCount, "tab")}, ${count(workspace.paneCount, "pane")}" to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClickLabel = "Open space",
                onLongClickLabel = "Space actions",
                onLongClick = { menu = true },
                onClick = onOpen,
            ),
    ) {
        Box {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                snapshot.activeTabId(workspace)?.let { TabMiniature(snapshot, it, MiniatureRow) }
                Column(Modifier.weight(1f)) {
                    Text(
                        workspace.label.ifBlank { "Space ${workspace.number}" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (needing.isNotEmpty()) FontWeight.Medium else null,
                        color = lineColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SpaceMenuItems(workspace, onAction) { menu = false }
            }
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
        Modifier.padding(top = 4.dp, start = 4.dp),
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
