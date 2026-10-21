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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabPanes
import dev.herdroid.ui.components.SnapshotBanner
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.StatusDot
import dev.herdroid.ui.components.SessionListRow
import dev.herdroid.ui.components.ListRowPosition
import dev.herdroid.ui.theme.Mono

/** Inside one space: its tabs, each listing its panes (agents and shells) in layout order. */
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
    val snapshotHealth by connection.snapshotHealth.collectAsState()
    val prompt by connection.prompt.collectAsState()
    val ui = rememberSessionUi(connection)
    var menu by remember { mutableStateOf(false) }
    val workspace = snapshot?.workspace(workspaceId)

    // The space was closed (here or on the desktop): go back up to the spaces.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(workspace, status, snapshotHealth.stale) {
        if (workspace != null) seen = true
        else if (seen && status == ConnectionStatus.Connected && !snapshotHealth.stale) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = maxOf(64.dp, 48.dp * LocalDensity.current.fontScale),
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
            if (status == ConnectionStatus.Connected) {
                SnapshotBanner(snapshotHealth, onRetry = { connection.requestRefresh() })
            }
            RefreshBox(connection) {
                val snap = snapshot
                if (snap == null || workspace == null) {
                    LoadingOrEmpty(status)
                } else {
                    SpaceContent(
                        snapshot = snap,
                        workspaceId = workspaceId,
                        stale = status !is ConnectionStatus.Connected || snapshotHealth.stale,
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
    val groups = snapshot.tabGroupsIn(workspaceId)
    // A lone tab without a name of its own is just the space: a plain list of its panes.
    val plain = groups.size == 1 && !groups[0].tab.hasCustomLabel
    LazyColumn(
        modifier = Modifier.fillMaxSize().alpha(if (stale) 0.6f else 1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (groups.none { group -> group.panes.any { snapshot.agentFor(it.paneId) != null } }) {
            item(key = "no-agents") {
                Text(
                    "No agents here yet. Open a pane and start one, like claude or codex.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                )
            }
        }
        groups.forEachIndexed { groupIndex, group ->
            item(key = "tab-" + group.tab.tabId) {
                TabHeader(
                    group = group,
                    plain = plain,
                    snapshot = snapshot,
                    onOpenPane = onOpenPane,
                    onAction = onAction,
                    onSplit = onSplit,
                    modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else 20.dp),
                )
            }
            itemsIndexed(group.panes, key = { _, it -> "pane-" + it.paneId }) { index, pane ->
                val agent = snapshot.agentFor(pane.paneId)
                val position = ListRowPosition.at(index, group.panes.size)
                if (agent != null) {
                    AgentRow(
                        agent = agent,
                        position = position,
                        canReply = !stale,
                        onOpen = { onOpenPane(pane.paneId) },
                        onReply = { onCompose(agent) },
                        onLongClick = { onPaneMenu(pane) },
                    )
                } else {
                    ShellRow(
                        pane = pane,
                        position = position,
                        onOpen = { onOpenPane(pane.paneId) },
                        onLongClick = { onPaneMenu(pane) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** A tab's name, state and menu, with its layout when it is split. [plain] keeps only the menu. */
@Composable
private fun TabHeader(
    group: TabPanes,
    plain: Boolean,
    snapshot: SessionSnapshot,
    onOpenPane: (String) -> Unit,
    onAction: (SessionDialog) -> Unit,
    onSplit: (PaneInfo, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab = group.tab
    var menu by remember { mutableStateOf(false) }
    Column(modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!plain) StatusDot(tab.agentStatus, size = 6.dp)
                Text(
                    if (plain) "Panes" else tab.displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (plain || tab.focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    group.panes.size.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, if (plain) "Tab actions" else "Actions for ${tab.displayLabel}", Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val target = group.panes.firstOrNull { it.focused } ?: group.panes.firstOrNull()
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
        // The miniature shows where the panes listed below sit; it is not a second list of them.
        // Touch still opens a tile, but screen readers get one node: the rows carry the actions.
        if ((snapshot.layout(tab.tabId)?.panes?.size ?: 0) > 1) {
            val description = "Layout of ${tab.displayLabel}, ${group.panes.size} panes"
            LayoutPreview(snapshot, tab.tabId, onOpenPane,
                Modifier.clearAndSetSemantics { contentDescription = description })
        }
    }
}

/** Agent and shell rows share a leading slot, so their names line up within a tab. */
@Composable
private fun LeadingSlot(content: @Composable () -> Unit) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { content() }
}

/** All agent states use one compact row, so state changes never resize or reorder the list. */
@Composable
private fun AgentRow(
    agent: AgentInfo,
    position: ListRowPosition,
    canReply: Boolean,
    onOpen: () -> Unit,
    onReply: () -> Unit,
    onLongClick: () -> Unit,
) {
    SessionListRow(
        onOpen = onOpen,
        onLongClick = onLongClick,
        actionLabel = "Pane actions",
        position = position,
        leading = { LeadingSlot { StatusDot(agent.agentStatus, size = 8.dp) } },
        focused = agent.focused,
        trailing = {
            IconButton(onClick = onReply, enabled = canReply) {
                Icon(Icons.AutoMirrored.Outlined.Chat, "Message ${agent.displayName}")
            }
        },
    ) {
        val largeText = LocalDensity.current.fontScale > 1.3f
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(agent.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!largeText) {
                Text(agent.statusLabel(), modifier = Modifier.widthIn(max = 96.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (largeText) {
            Text(agent.statusLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        agent.subtitle?.takeUnless { it == agent.displayName }?.let { detail ->
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A pane without an agent (shell, server, logs): terminal glyph, title and working directory. */
@Composable
private fun ShellRow(pane: PaneInfo, position: ListRowPosition, onOpen: () -> Unit, onLongClick: () -> Unit) {
    SessionListRow(
        onOpen = onOpen,
        onLongClick = onLongClick,
        actionLabel = "Pane actions",
        position = position,
        leading = {
            LeadingSlot { Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        focused = pane.focused,
    ) {
        Text(pane.displayTitle, style = MaterialTheme.typography.titleMedium, fontFamily = Mono,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(
            pane.terminalTitleStripped?.takeIf { it.isNotBlank() && it != pane.displayTitle },
            pane.foregroundCwd ?: pane.cwd,
        ).firstOrNull()
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (detail.startsWith("/") || detail.startsWith("~")) Mono else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
