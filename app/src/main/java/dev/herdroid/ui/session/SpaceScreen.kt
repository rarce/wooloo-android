package dev.herdroid.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.StatusDot
import dev.herdroid.ui.components.StatusPill
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono

/** Inside one space: its agents first (whoever needs you on top), then its tabs and shells. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    hostId: String,
    workspaceId: String,
    onBack: () -> Unit,
    onOpenPane: (String) -> Unit,
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
    val workspace = snapshot?.workspace(workspaceId)

    // The space was closed (here or on the desktop): go back up to the spaces.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(workspace, status) {
        if (workspace != null) seen = true
        else if (seen && status == ConnectionStatus.Connected) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(workspace?.label ?: "Space", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            connection.profile.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to spaces") }
                },
                actions = {
                    if (workspace != null) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Space actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                SpaceMenuItems(workspace, onAction = { ui.dialog = it }) { menu = false }
                            }
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
                if (snap == null || workspace == null) {
                    LoadingOrEmpty(status)
                } else {
                    SpaceContent(
                        snapshot = snap,
                        workspaceId = workspaceId,
                        stale = status !is ConnectionStatus.Connected,
                        onOpenPane = onOpenPane,
                        onPaneMenu = { ui.paneSheet = it },
                        onCompose = { ui.composeFor = it },
                        onAction = { ui.dialog = it },
                        onSplit = { pane, dir -> ui.act("Split") { splitPane(pane.paneId, dir) } },
                    )
                }
            }
        }
    }

    ConnectionPrompts(connection, prompt)
    SessionOverlays(ui, connection, onOpenPane)
}

@Composable
internal fun SpaceContent(
    snapshot: SessionSnapshot,
    workspaceId: String,
    stale: Boolean,
    onOpenPane: (String) -> Unit,
    onPaneMenu: (PaneInfo) -> Unit,
    onCompose: (AgentInfo) -> Unit,
    onAction: (SessionDialog) -> Unit,
    onSplit: (PaneInfo, String) -> Unit,
) {
    val status = LocalStatusColors.current
    val agents = snapshot.agentsIn(workspaceId)
    val tabs = snapshot.tabsOf(workspaceId)
    // A tab earns its own section when it has more to show than the agents above already do.
    val tabSections = tabs.filter { tab ->
        tabs.size > 1 || (snapshot.layout(tab.tabId)?.panes?.size ?: 0) > 1 ||
            snapshot.panesOf(tab.tabId).any { snapshot.agentFor(it.paneId) == null }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().alpha(if (stale) 0.6f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "agents-title") { SectionLabel("Agents", agents.size) }
        if (agents.isEmpty()) {
            item(key = "no-agents") {
                Text(
                    "No agents here yet. Open a pane and start one, like claude or codex.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(agents, key = { "agent-" + it.paneId }) { agent ->
            val tab = snapshot.tab(agent.tabId)?.takeIf { it.hasCustomLabel || tabs.size > 1 }
            if (status.isSolid(agent.agentStatus)) {
                AttentionCard(
                    agent = agent,
                    snapshot = snapshot,
                    tab = tab,
                    onOpen = { onOpenPane(agent.paneId) },
                    onReply = { onCompose(agent) },
                )
            } else {
                snapshot.pane(agent.paneId)?.let { pane ->
                    PaneRow(
                        pane = pane,
                        agent = agent,
                        onClick = { onOpenPane(pane.paneId) },
                        onLongClick = { onPaneMenu(pane) },
                    )
                }
            }
        }
        if (tabSections.isNotEmpty()) {
            item(key = "tabs-title") {
                SectionLabel(if (tabs.size > 1) "Tabs" else "Layout", tabs.size)
            }
            items(tabSections, key = { "tab-" + it.tabId }) { tab ->
                TabSection(tab, snapshot, onOpenPane, onPaneMenu, onAction, onSplit)
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** An agent that needs you: what it's asking, and the answer as the main action. */
@Composable
private fun AttentionCard(
    agent: AgentInfo,
    snapshot: SessionSnapshot,
    tab: TabInfo?,
    onOpen: () -> Unit,
    onReply: () -> Unit,
) {
    val status = LocalStatusColors.current
    val kind = (agent.displayAgent ?: agent.agent)?.takeIf { !it.equals(agent.displayName, ignoreCase = true) }
    val detail = listOfNotNull(kind, tab?.displayLabel).joinToString(" in ")
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
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusPill(agent.agentStatus, agent.statusLabel())
            }
            agent.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // The answer is the main action and takes the agent's color; opening the pane is secondary.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onReply,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = status.of(agent.agentStatus),
                        contentColor = status.onSolid,
                    ),
                ) { Text(if (agent.agentStatus == AgentStatus.BLOCKED) "Reply" else "Follow up") }
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

/** A tab's layout and its non-agent panes (shells, servers, logs); agents are listed above. */
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
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    tab.displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (tab.focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, "Tab actions", Modifier.size(20.dp))
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
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((snapshot.layout(tab.tabId)?.panes?.size ?: 0) > 1) {
                    LayoutPreview(snapshot, tab.tabId, onOpenPane)
                }
                panes.filter { snapshot.agentFor(it.paneId) == null }.forEach { pane ->
                    PaneRow(pane = pane, agent = null, onClick = { onOpenPane(pane.paneId) }, onLongClick = { onPaneMenu(pane) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneRow(pane: PaneInfo, agent: AgentInfo?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val status = agent?.agentStatus
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClickLabel = "Pane actions", onLongClick = onLongClick),
        colors = ListItemDefaults.colors(
            containerColor = if (agent != null) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        leadingContent = {
            if (status != null) StatusDot(status, size = 9.dp)
            else Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = {
            Text(
                agent?.displayName ?: pane.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (agent != null || pane.focused) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (agent == null) Mono else null,
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
