package dev.herdroid.ui.session

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.WorkspaceInfo
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.components.ConfirmDialog
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.components.FieldSpec
import dev.herdroid.ui.components.FormDialog
import dev.herdroid.ui.components.StatusDot
import dev.herdroid.ui.components.StatusPill
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono
import kotlinx.coroutines.launch

/** Pending UI dialog for the session screen. */
internal sealed interface SessionDialog {
    data object NewWorkspace : SessionDialog
    data class NewTab(val workspace: WorkspaceInfo) : SessionDialog
    data class RenameWorkspace(val workspace: WorkspaceInfo) : SessionDialog
    data class CloseWorkspace(val workspace: WorkspaceInfo) : SessionDialog
    data class RenameTab(val tab: TabInfo) : SessionDialog
    data class CloseTab(val tab: TabInfo) : SessionDialog
    data class RenamePane(val pane: PaneInfo) : SessionDialog
    data class ClosePane(val pane: PaneInfo) : SessionDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    hostId: String,
    onBack: () -> Unit,
    onOpenPane: (String) -> Unit,
    onEditHost: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val connection = remember(hostId) { app.connections.connect(hostId) }
    if (connection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val status by connection.status.collectAsState()
    val snapshot by connection.snapshot.collectAsState()
    val prompt by connection.prompt.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SessionDialog?>(null) }
    var paneSheet by remember { mutableStateOf<PaneInfo?>(null) }
    var composeFor by remember { mutableStateOf<AgentInfo?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun act(label: String, block: suspend dev.herdroid.herdr.HerdrApi.() -> Unit) {
        scope.launch {
            connection.action(block).onFailure {
                Toast.makeText(context, "$label failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { dialog = SessionDialog.NewWorkspace }, enabled = connection.isLive) {
                        Icon(Icons.Outlined.DashboardCustomize, "New workspace")
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
            ) {
                val snap = snapshot
                when {
                    snap == null -> LoadingOrEmpty(status)
                    snap.workspaces.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Workspaces,
                        title = "No workspaces yet",
                        body = "This herdr session is empty. Create a workspace to start a shell or an agent.",
                    ) {
                        FilledTonalButton(onClick = { dialog = SessionDialog.NewWorkspace }) { Text("New workspace") }
                    }
                    else -> SessionContent(
                        snapshot = snap,
                        stale = status !is ConnectionStatus.Connected,
                        onOpenPane = onOpenPane,
                        onPaneMenu = { paneSheet = it },
                        onCompose = { composeFor = it },
                        onWorkspaceAction = { dialog = it },
                        onSplit = { pane, dir -> act("Split") { splitPane(pane.paneId, dir) } },
                    )
                }
            }
        }
    }

    ConnectionPrompts(connection, prompt)

    paneSheet?.let { pane ->
        PaneActionsSheet(
            pane = pane,
            onDismiss = { paneSheet = null },
            onOpen = { paneSheet = null; onOpenPane(pane.paneId) },
            onRename = { paneSheet = null; dialog = SessionDialog.RenamePane(pane) },
            onZoom = { paneSheet = null; act("Zoom") { zoomPane(pane.paneId) } },
            onSplit = { dir -> paneSheet = null; act("Split") { splitPane(pane.paneId, dir) } },
            onClose = { paneSheet = null; dialog = SessionDialog.ClosePane(pane) },
        )
    }

    composeFor?.let { agent ->
        ComposerSheet(
            title = "Message ${agent.displayName}",
            agentStatus = agent.agentStatus,
            isAgent = true,
            onDismiss = { composeFor = null },
            onQuickReply = { reply -> act("Send") { sendText(agent.paneId, reply.input) } },
            onSend = { text -> act("Prompt") { promptAgent(agent.paneId, text) } },
        )
    }

    when (val d = dialog) {
        null -> Unit
        SessionDialog.NewWorkspace -> FormDialog(
            title = "New workspace",
            fields = listOf(FieldSpec("Name", placeholder = "api"), FieldSpec("Directory", placeholder = "~/project (optional)")),
            confirm = "Create",
            onDismiss = { dialog = null },
        ) { (label, cwd) ->
            dialog = null
            act("Create workspace") { createWorkspace(label, cwd.takeIf { it.isNotBlank() }?.let(connection::expandPath)) }
        }
        is SessionDialog.NewTab -> FormDialog(
            title = "New tab in ${d.workspace.label}",
            fields = listOf(FieldSpec("Name", placeholder = "logs")),
            confirm = "Create",
            onDismiss = { dialog = null },
        ) { (label) -> dialog = null; act("Create tab") { createTab(d.workspace.workspaceId, label) } }
        is SessionDialog.RenameWorkspace -> FormDialog(
            title = "Rename workspace",
            fields = listOf(FieldSpec("Name", d.workspace.label)),
            confirm = "Rename",
            requireFirst = true,
            onDismiss = { dialog = null },
        ) { (label) -> dialog = null; act("Rename") { renameWorkspace(d.workspace.workspaceId, label) } }
        is SessionDialog.CloseWorkspace -> ConfirmDialog(
            title = "Close ${d.workspace.label}?",
            body = "Closes ${count(d.workspace.tabCount, "tab")} and ${count(d.workspace.paneCount, "pane")}, stopping their processes.",
            confirm = "Close workspace",
            onDismiss = { dialog = null },
        ) { act("Close workspace") { closeWorkspace(d.workspace.workspaceId) } }
        is SessionDialog.RenameTab -> FormDialog(
            title = "Rename tab",
            fields = listOf(FieldSpec("Name", d.tab.label.takeIf { d.tab.hasCustomLabel }.orEmpty())),
            confirm = "Rename",
            requireFirst = true,
            onDismiss = { dialog = null },
        ) { (label) -> dialog = null; act("Rename") { renameTab(d.tab.tabId, label) } }
        is SessionDialog.CloseTab -> ConfirmDialog(
            title = "Close ${d.tab.displayLabel}?",
            body = "Closes ${count(d.tab.paneCount, "pane")} and stops their processes.",
            confirm = "Close tab",
            onDismiss = { dialog = null },
        ) { act("Close tab") { closeTab(d.tab.tabId) } }
        is SessionDialog.RenamePane -> FormDialog(
            title = "Rename pane",
            fields = listOf(FieldSpec("Label", d.pane.label.orEmpty())),
            confirm = "Rename",
            onDismiss = { dialog = null },
        ) { (label) -> dialog = null; act("Rename") { renamePane(d.pane.paneId, label) } }
        is SessionDialog.ClosePane -> ConfirmDialog(
            title = "Close ${d.pane.displayTitle}?",
            body = "The pane's process will be stopped.",
            confirm = "Close pane",
            onDismiss = { dialog = null },
        ) { act("Close pane") { closePane(d.pane.paneId) } }
    }
}

private fun subtitle(status: ConnectionStatus, snapshot: SessionSnapshot?, connection: HerdrConnection): String =
    when (status) {
        ConnectionStatus.Connected -> buildString {
            append("Session ").append(connection.profile.session)
            snapshot?.let {
                append(", ").append(count(it.workspaces.size, "workspace"))
                if (it.version.isNotBlank()) append(", herdr ").append(it.version)
            }
        }
        is ConnectionStatus.Connecting -> "Connecting…"
        is ConnectionStatus.Reconnecting -> "Offline — showing last known state"
        is ConnectionStatus.Failed -> "Not connected"
        ConnectionStatus.Idle -> ""
    }

@Composable
private fun LoadingOrEmpty(status: ConnectionStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (status is ConnectionStatus.Connecting || status is ConnectionStatus.Reconnecting) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator()
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
internal fun SessionContent(
    snapshot: SessionSnapshot,
    stale: Boolean,
    onOpenPane: (String) -> Unit,
    onPaneMenu: (PaneInfo) -> Unit,
    onCompose: (AgentInfo) -> Unit,
    onWorkspaceAction: (SessionDialog) -> Unit,
    onSplit: (PaneInfo, String) -> Unit,
) {
    val attention = snapshot.agentsByAttention.filter {
        it.agentStatus == AgentStatus.BLOCKED || it.agentStatus == AgentStatus.DONE
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().alpha(if (stale) 0.6f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (attention.isNotEmpty()) {
            item(key = "attention-title") { SectionLabel("Needs you", attention.size) }
            items(attention, key = { "att-" + it.paneId }) { agent ->
                AttentionCard(
                    agent = agent,
                    snapshot = snapshot,
                    onOpen = { onOpenPane(agent.paneId) },
                    onReply = { onCompose(agent) },
                )
            }
        }
        item(key = "ws-title") { SectionLabel("Workspaces", snapshot.workspaces.size) }
        items(snapshot.workspaces, key = { "ws-" + it.workspaceId }) { workspace ->
            val isExpanded = expanded[workspace.workspaceId]
                ?: (workspace.focused || snapshot.workspaces.size <= 2 ||
                    workspace.agentStatus == AgentStatus.BLOCKED)
            WorkspaceCard(
                workspace = workspace,
                snapshot = snapshot,
                expanded = isExpanded,
                onToggle = { expanded[workspace.workspaceId] = !isExpanded },
                onOpenPane = onOpenPane,
                onPaneMenu = onPaneMenu,
                onAction = onWorkspaceAction,
                onSplit = onSplit,
            )
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
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
private fun SectionLabel(text: String, count: Int) {
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

@Composable
private fun AttentionCard(
    agent: AgentInfo,
    snapshot: SessionSnapshot,
    onOpen: () -> Unit,
    onReply: () -> Unit,
) {
    val status = LocalStatusColors.current
    val kind = (agent.displayAgent ?: agent.agent)?.takeIf { !it.equals(agent.displayName, ignoreCase = true) }
    val where = breadcrumb(snapshot.workspace(agent.workspaceId), snapshot.tab(agent.tabId))
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TabMiniature(snapshot, agent.tabId, MiniatureRow, highlightPaneId = agent.paneId)
                Column(Modifier.weight(1f)) {
                    Text(agent.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(kind, where.ifBlank { null }).joinToString(" in "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            agent.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // The answer is the main action and takes the agent's color; opening the pane is secondary.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isSolid(agent.agentStatus)) {
                    Button(
                        onClick = onReply,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = status.of(agent.agentStatus),
                            contentColor = status.onSolid,
                        ),
                    ) { Text(if (agent.agentStatus == AgentStatus.BLOCKED) "Reply" else "Follow up") }
                }
                OutlinedButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) { Text("Open pane", fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCard(
    workspace: WorkspaceInfo,
    snapshot: SessionSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenPane: (String) -> Unit,
    onPaneMenu: (PaneInfo) -> Unit,
    onAction: (SessionDialog) -> Unit,
    onSplit: (PaneInfo, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (expanded) "Collapse" else "Expand",
                    onLongClickLabel = "Workspace actions",
                    onLongClick = { menu = true },
                    onClick = onToggle,
                )
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val activeTab = snapshot.activeTabId(workspace)
            if (activeTab != null) TabMiniature(snapshot, activeTab, MiniatureRow)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(workspace.label.ifBlank { "workspace ${workspace.number}" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${count(workspace.tabCount, "tab")}, ${count(workspace.paneCount, "pane")}" +
                        if (workspace.focused) ", focused" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        onClick = { menu = false; onAction(SessionDialog.NewTab(workspace)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onAction(SessionDialog.RenameWorkspace(workspace)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Close workspace") },
                        leadingIcon = { Icon(Icons.Outlined.Close, null) },
                        onClick = { menu = false; onAction(SessionDialog.CloseWorkspace(workspace)) },
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                snapshot.tabsOf(workspace.workspaceId).forEachIndexed { index, tab ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    TabSection(tab, snapshot, onOpenPane, onPaneMenu, onAction, onSplit)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabSection(
    tab: TabInfo,
    snapshot: SessionSnapshot,
    onOpenPane: (String) -> Unit,
    onPaneMenu: (PaneInfo) -> Unit,
    onAction: (SessionDialog) -> Unit,
    onSplit: (PaneInfo, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val panes = snapshot.panesOf(tab.tabId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Text(
                tab.displayLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (tab.focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.MoreVert, "Tab actions", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val target = panes.firstOrNull { it.focused } ?: panes.firstOrNull()
                    if (target != null) {
                        DropdownMenuItem(
                            text = { Text("Split right") },
                            leadingIcon = { Icon(Icons.Outlined.ViewColumn, null) },
                            onClick = { menu = false; onSplit(target, "right") },
                        )
                        DropdownMenuItem(
                            text = { Text("Split down") },
                            leadingIcon = { Icon(Icons.Outlined.ViewAgenda, null) },
                            onClick = { menu = false; onSplit(target, "down") },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename tab") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onAction(SessionDialog.RenameTab(tab)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Close tab") },
                        leadingIcon = { Icon(Icons.Outlined.Close, null) },
                        onClick = { menu = false; onAction(SessionDialog.CloseTab(tab)) },
                    )
                }
            }
        }
        if ((snapshot.layout(tab.tabId)?.panes?.size ?: 0) > 1) {
            LayoutPreview(snapshot, tab.tabId, onOpenPane)
        }
        panes.forEach { pane ->
            PaneRow(
                pane = pane,
                agent = snapshot.agentFor(pane.paneId),
                onClick = { onOpenPane(pane.paneId) },
                onLongClick = { onPaneMenu(pane) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneRow(pane: PaneInfo, agent: AgentInfo?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val status = agent?.agentStatus
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            if (status != null) StatusDot(status, size = 9.dp)
            else Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = {
            Text(
                agent?.displayName ?: pane.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (pane.focused) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        supportingContent = {
            val detail = listOfNotNull(
                agent?.subtitle ?: pane.terminalTitleStripped?.takeIf { agent == null && it != pane.displayTitle },
                pane.foregroundCwd ?: pane.cwd,
            ).firstOrNull()
            if (detail != null) {
                Text(
                    detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (detail.startsWith("/") || detail.startsWith("~")) Mono else null,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status != null) StatusPill(status, agent.statusLabel())
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneActionsSheet(
    pane: PaneInfo,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onZoom: () -> Unit,
    onSplit: (String) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                pane.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetItem(Icons.Outlined.Terminal, "Open terminal", onOpen)
            SheetItem(Icons.Outlined.Edit, "Rename", onRename)
            SheetItem(Icons.Outlined.CropFree, "Toggle zoom", onZoom)
            SheetItem(Icons.Outlined.ViewColumn, "Split right") { onSplit("right") }
            SheetItem(Icons.Outlined.ViewAgenda, "Split down") { onSplit("down") }
            SheetItem(Icons.Outlined.Close, "Close pane", onClose)
        }
    }
}

@Composable
fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
