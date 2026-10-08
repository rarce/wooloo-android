package dev.herdroid.ui.terminal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import dev.herdroid.ui.components.StatusDot
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.RemoteCommand
import dev.herdroid.terminal.TerminalView
import dev.herdroid.terminal.TerminalViewListener
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.components.ConfirmDialog
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.FieldSpec
import dev.herdroid.ui.components.FormDialog
import dev.herdroid.ui.components.ImageAttachments
import dev.herdroid.ui.components.StatusPill
import dev.herdroid.ui.components.fadingEdges
import dev.herdroid.ui.session.SheetItem
import dev.herdroid.ui.session.breadcrumb
import dev.herdroid.ui.theme.WoolooMobileTheme
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono
import kotlinx.coroutines.launch

/**
 * The terminal is always dark, whatever the system theme: light chrome around a dark terminal
 * reads as two different apps.
 */
@Composable
fun TerminalScreen(
    hostId: String,
    initialPaneId: String,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onOpenInSpace: (workspaceId: String, paneId: String) -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { bars.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars.isAppearanceLightNavigationBars = it }
        }
    }
    WoolooMobileTheme(darkTheme = true) {
        // Surface resets LocalContentColor, which otherwise leaks in from the app's light theme.
        Surface(color = MaterialTheme.colorScheme.surface) {
            TerminalScreenContent(hostId, initialPaneId, onBack, onEditHost, onOpenInSpace)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalScreenContent(
    hostId: String,
    initialPaneId: String,
    onBack: () -> Unit,
    onEditHost: () -> Unit,
    onOpenInSpace: (workspaceId: String, paneId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val connection = remember(hostId) { app.connections.connect(hostId) }
    if (connection == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var paneId by rememberSaveable { mutableStateOf(initialPaneId) }
    val status by connection.status.collectAsState()
    val snapshot by connection.snapshot.collectAsState()
    val prompt by connection.prompt.collectAsState()
    val fontSize by app.preferences.fontSize.collectAsState()
    val keepScreenOn by app.preferences.keepScreenOn.collectAsState()
    val haptics by app.preferences.haptics.collectAsState()
    val pane = snapshot?.pane(paneId)
    val agent = snapshot?.agentFor(paneId)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }

    val controller = remember(hostId, paneId) { TerminalController(connection, paneId) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var composer by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var longPress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val retainInputFocus = lifecycleState == Lifecycle.State.RESUMED &&
        controller.state is PaneState.Live && !controller.readOnly &&
        !composer && !menu && longPress == null && !renaming && !closing && prompt == null

    // Keep the screen awake while watching a pane, if enabled.
    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Surface other agents that need attention without leaving this pane.
    LaunchedEffect(connection) {
        connection.alerts.collect { alert ->
            if (alert.agent.paneId == paneId) return@collect
            val here = connection.snapshot.value?.pane(paneId)?.workspaceId
            val elsewhere = alert.agent.workspaceId != here
            val verb = if (alert.agent.agentStatus == AgentStatus.BLOCKED) "needs input" else "finished"
            val where = alert.workspaceLabel?.takeIf { elsewhere }?.let { " in $it" }.orEmpty()
            val result = snackbar.showSnackbar(
                message = "${alert.agent.displayName}$where $verb",
                actionLabel = "Open",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Same space: switch in place. Another space: go through it, so back leads there.
                if (elsewhere) onOpenInSpace(alert.agent.workspaceId, alert.agent.paneId)
                else paneId = alert.agent.paneId
            }
        }
    }

    fun act(label: String, block: suspend dev.herdroid.herdr.HerdrApi.() -> Unit) {
        scope.launch {
            connection.action(block).onFailure {
                Toast.makeText(context, "$label failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // A pane that disappears from the session (closed elsewhere) sends us back. Only once we
    // have seen it: a pane we just created by splitting isn't in the snapshot yet.
    var paneSeen by remember(paneId) { mutableStateOf(false) }
    LaunchedEffect(snapshot, paneId) {
        val snap = snapshot ?: return@LaunchedEffect
        if (snap.pane(paneId) != null) {
            paneSeen = true
        } else if (paneSeen && status == ConnectionStatus.Connected) {
            Toast.makeText(context, "That pane was closed", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            TerminalTopBar(
                pane = pane,
                snapshot = snapshot,
                agentName = agent?.displayName,
                agentStatus = agent?.agentStatus,
                agentLabel = agent?.statusLabel(),
                onBack = onBack,
                menu = menu,
                onMenu = { menu = it },
                readOnly = controller.readOnly,
                onToggleReadOnly = { controller.setObserveOnly(!controller.readOnly) },
                onFont = { delta ->
                    val next = (fontSize + delta).coerceIn(TerminalView.MIN_FONT_SP, TerminalView.MAX_FONT_SP)
                    app.preferences.setFontSize(next)
                    terminalView?.setFontSize(next)
                },
                onCopy = {
                    clipboard.setText(AnnotatedString(controller.model.screenText()))
                    Toast.makeText(context, "Screen copied", Toast.LENGTH_SHORT).show()
                },
                onZoom = { act("Zoom") { zoomPane(paneId) } },
                onSplit = { dir ->
                    scope.launch {
                        connection.action { splitPane(paneId, dir) }
                            .onSuccess { created -> created?.paneId?.let { paneId = it } }
                            .onFailure { Toast.makeText(context, "Split failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                },
                onRename = { renaming = true },
                onClosePane = { closing = true },
                onReconnect = { controller.open() },
                onSwipeAgent = { step ->
                    val panes = snapshot?.let { snap -> pane?.let { snap.panesInLayoutOrder(it.workspaceId) } }.orEmpty()
                    val index = panes.indexOfFirst { it.paneId == paneId }
                    panes.getOrNull(if (index < 0) 0 else (index + step).mod(panes.size))?.let { paneId = it.paneId }
                },
            )
            ConnectionBanner(status, onRetry = { connection.retryNow() }, onEditHost = onEditHost)
            snapshot?.let { snap ->
                pane?.let { PaneSwitcher(snap, it) { id -> paneId = id } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(controller) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, controller.model, controller.modifiers).apply {
                            setPadding(8, 4, 8, 4)
                            setFontSize(fontSize, notify = false)
                        }
                    },
                    update = { view ->
                        terminalView = view
                        view.inputEnabled = retainInputFocus
                        view.retainInputFocus = retainInputFocus
                        view.listener = object : TerminalViewListener {
                            override fun onInput(data: String) = controller.input(data)
                            override fun onGridSizeChanged(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) =
                                controller.onGridSize(cols, rows, cellWidthPx, cellHeightPx)
                            override fun onScroll(up: Boolean, lines: Int, column: Int, row: Int) =
                                controller.scroll(up, lines, column, row)
                            override fun onTap(column: Int, row: Int) {
                                val link = controller.model.linkAt(column, row)
                                if (link != null) longPress = column to row else controller.tap(column, row)
                            }
                            override fun onLongPress(column: Int, row: Int) { longPress = column to row }
                            override fun onFontSizeChanged(sp: Float) = app.preferences.setFontSize(sp)
                        }
                        // Re-report the grid so a freshly created controller (pane switch) opens its stream.
                        val (cols, rows) = view.currentGrid()
                        if (cols > 0 && controller.state is PaneState.WaitingForLayout) {
                            controller.onGridSize(cols, rows, 0, 0)
                        }
                    },
                    onRelease = { view ->
                        view.retainInputFocus = false
                        view.listener = null
                        if (terminalView === view) terminalView = null
                    },
                )
                }
                PaneOverlay(controller.state, onTakeOver = { controller.open(takeover = true) },
                    onWatch = { controller.open(observeOnly = true) },
                    onRetry = { controller.open() },
                    onBack = onBack)
            }
            if (agent?.agentStatus == AgentStatus.BLOCKED && !controller.readOnly) {
                QuickAnswers(onAnswer = { terminalView?.sendText(it) })
            }
            ExtraKeysBar(
                modifiers = controller.modifiers,
                haptics = haptics,
                onSpecial = { key -> terminalView?.sendSpecial(key) },
                onText = { text -> terminalView?.sendText(text) },
                onModifier = { terminalView?.prepareModifierInput() },
                onPaste = {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { text ->
                        // Bracketed paste keeps shells and agents from executing line by line.
                        terminalView?.sendPaste(text)
                    }
                },
                onToggleKeyboard = {
                    val view = terminalView ?: return@ExtraKeysBar
                    val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    if (imeVisible) view.hideKeyboard() else view.showKeyboard()
                },
                onComposer = { composer = true },
                composerHighlighted = agent?.agentStatus == AgentStatus.BLOCKED,
            )
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
        ) { data -> Snackbar(data, shape = RoundedCornerShape(14.dp)) }
    }

    ConnectionPrompts(connection, prompt)

    if (composer) {
        val isAgent = agent != null
        ComposerSheet(
            title = if (isAgent) "Message ${agent!!.displayName}" else "Send to ${pane?.displayTitle ?: paneId}",
            agentStatus = agent?.agentStatus,
            isAgent = isAgent,
            onDismiss = { composer = false },
            onQuickReply = { reply -> controller.input(reply.input) },
            onSend = { text, images ->
                when {
                    isAgent -> act("Prompt") {
                        promptAgent(paneId, ImageAttachments.withPaths(text, ImageAttachments.upload(connection, images)))
                    }
                    images.isEmpty() -> controller.input(text.replace("\n", "\r") + "\r")
                    else -> act("Send") {
                        val paths = ImageAttachments.upload(connection, images).joinToString(" ") { RemoteCommand.shellQuote(it) }
                        controller.input(listOf(text.trim(), paths).filter { it.isNotEmpty() }.joinToString(" ").replace("\n", "\r") + "\r")
                    }
                }
            },
        )
    }

    longPress?.let { (col, row) ->
        val link = controller.model.linkAt(col, row)
        val line = controller.model.rowText(row)
        ModalBottomSheet(onDismissRequest = { longPress = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                if (line.isNotBlank()) {
                    Text(
                        line,
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                if (link != null) {
                    SheetItem(Icons.Outlined.OpenInBrowser, "Open link") {
                        longPress = null
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    }
                    SheetItem(Icons.Outlined.ContentCopy, "Copy link") {
                        longPress = null
                        clipboard.setText(AnnotatedString(link))
                    }
                }
                SheetItem(Icons.Outlined.ContentCopy, "Copy line") {
                    longPress = null
                    clipboard.setText(AnnotatedString(line))
                }
                SheetItem(Icons.Outlined.ContentCopy, "Copy screen") {
                    longPress = null
                    clipboard.setText(AnnotatedString(controller.model.screenText()))
                }
                SheetItem(Icons.Outlined.ContentCopy, "Copy recent output (400 lines)") {
                    longPress = null
                    scope.launch {
                        connection.action { readPane(paneId) }
                            .onSuccess {
                                clipboard.setText(AnnotatedString(it))
                                Toast.makeText(context, "Copied ${it.lines().size} lines", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            }
        }
    }

    if (renaming) {
        FormDialog(
            title = "Rename pane",
            fields = listOf(FieldSpec("Label", pane?.label.orEmpty())),
            confirm = "Rename",
            onDismiss = { renaming = false },
        ) { (label) -> renaming = false; act("Rename") { renamePane(paneId, label) } }
    }
    if (closing) {
        ConfirmDialog(
            title = "Close this pane?",
            body = "Its process will be stopped.",
            confirm = "Close pane",
            onDismiss = { closing = false },
        ) {
            act("Close pane") { closePane(paneId) }
            onBack()
        }
    }
}

@Composable
internal fun TerminalTopBar(
    pane: PaneInfo?,
    snapshot: SessionSnapshot?,
    agentName: String?,
    agentStatus: AgentStatus?,
    agentLabel: String?,
    onBack: () -> Unit,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    readOnly: Boolean,
    onToggleReadOnly: () -> Unit,
    onFont: (Float) -> Unit,
    onCopy: () -> Unit,
    onZoom: () -> Unit,
    onSplit: (String) -> Unit,
    onRename: () -> Unit,
    onClosePane: () -> Unit,
    onReconnect: () -> Unit,
    onSwipeAgent: ((Int) -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        // Swipe the title sideways to move between this space's agents.
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (onSwipeAgent == null) Modifier
                    else Modifier
                        .pointerInput(onSwipeAgent) {
                            var dragged = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = {
                                    if (dragged <= -SWIPE_PX) onSwipeAgent(1) else if (dragged >= SWIPE_PX) onSwipeAgent(-1)
                                },
                            ) { _, delta -> dragged += delta }
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Next agent in this space") { onSwipeAgent(1); true },
                                CustomAccessibilityAction("Previous agent in this space") { onSwipeAgent(-1); true },
                            )
                        }
                ),
        ) {
            Text(
                agentName ?: pane?.displayTitle ?: "Terminal",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pane?.let { breadcrumb(snapshot?.workspace(it.workspaceId), snapshot?.tab(it.tabId), it.shortCwd) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (agentStatus != null && agentLabel != null) StatusPill(agentStatus, agentLabel)
        if (readOnly) {
            Icon(Icons.Outlined.Visibility, "Watching", Modifier.padding(horizontal = 6.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Outlined.MoreVert, "Pane actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onFont(-1f) }) { Icon(Icons.Outlined.TextDecrease, "Smaller text") }
                    IconButton(onClick = { onFont(1f) }) { Icon(Icons.Outlined.TextIncrease, "Larger text") }
                }
                HorizontalDivider()
                MenuItem(Icons.Outlined.ContentCopy, "Copy screen") { onMenu(false); onCopy() }
                MenuItem(if (readOnly) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (readOnly) "Take control" else "Watch only") { onMenu(false); onToggleReadOnly() }
                MenuItem(Icons.Outlined.CropFree, "Toggle zoom") { onMenu(false); onZoom() }
                MenuItem(Icons.Outlined.ViewColumn, "Split right") { onMenu(false); onSplit("right") }
                MenuItem(Icons.Outlined.ViewAgenda, "Split down") { onMenu(false); onSplit("down") }
                MenuItem(Icons.Outlined.Edit, "Rename pane") { onMenu(false); onRename() }
                MenuItem(Icons.Outlined.Refresh, "Reconnect pane") { onMenu(false); onReconnect() }
                MenuItem(Icons.Outlined.Close, "Close pane") { onMenu(false); onClosePane() }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/**
 * Every pane of the current space, agents and ordinary terminals alike, in layout order: herdr's
 * state mark for agents, a terminal glyph for shells. The current pane is highlighted.
 */
@Composable
internal fun PaneSwitcher(snapshot: SessionSnapshot, current: PaneInfo, onSelect: (String) -> Unit) {
    // Only this space's panes: changing space means going back up to the spaces list.
    val panes = snapshot.panesInLayoutOrder(current.workspaceId)
    if (panes.size <= 1) return
    val listState = rememberLazyListState()
    LaunchedEffect(current.paneId) {
        panes.indexOfFirst { it.paneId == current.paneId }.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) }
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fadingEdges(listState),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(panes, key = { it.paneId }) { pane ->
            val agent = snapshot.agentFor(pane.paneId)
            val selected = pane.paneId == current.paneId
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .then(if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                    .clickable(enabled = !selected) { onSelect(pane.paneId) }
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (agent != null) {
                    StatusDot(agent.agentStatus, size = 9.dp)
                } else {
                    Box(Modifier.size(18.dp + 4.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Terminal, null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    agent?.displayName ?: pane.displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontFamily = if (agent == null) Mono else null,
                    color = if (selected || agent != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
    }
}

/** One-tap answers for a blocked agent's numbered prompt, above the extra keys (which have esc). */
@Composable
internal fun QuickAnswers(onAnswer: (String) -> Unit) {
    val blocked = LocalStatusColors.current.blocked
    val onSolid = LocalStatusColors.current.onSolid
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("1", "2", "3").forEachIndexed { index, answer ->
            val first = index == 0
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .then(if (first) Modifier.background(blocked) else Modifier.border(1.dp, blocked, shape))
                    .clickable(onClickLabel = "Answer $answer") { onAnswer(answer) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    answer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (first) FontWeight.Bold else FontWeight.Medium,
                    color = if (first) onSolid else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PaneOverlay(
    state: PaneState,
    onTakeOver: () -> Unit,
    onWatch: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    when (state) {
        PaneState.Live -> return
        PaneState.WaitingForLayout, PaneState.Opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        else -> Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)).clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(24.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (title, body) = when (state) {
                        is PaneState.Busy -> "Pane in use" to "Another client is controlling this pane (probably herdr on your computer). Take control here, or just watch."
                        PaneState.TakenOver -> "Control moved" to "Another client took control of this pane."
                        PaneState.Offline -> "Offline" to "Waiting for the connection to come back…"
                        is PaneState.Closed -> "Stream ended" to (state.reason ?: "The pane stream closed.")
                        else -> "" to ""
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    when (state) {
                        is PaneState.Busy, PaneState.TakenOver -> {
                            Button(onClick = onTakeOver, modifier = Modifier.fillMaxWidth()) { Text("Take control") }
                            OutlinedButton(onClick = onWatch, modifier = Modifier.fillMaxWidth()) { Text("Watch only") }
                        }
                        PaneState.Offline -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        is PaneState.Closed -> {
                            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to session") }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

private const val SWIPE_PX = 120f
