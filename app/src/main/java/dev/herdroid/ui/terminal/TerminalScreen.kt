package dev.herdroid.ui.terminal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import dev.herdroid.ui.components.StatusDot
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitScreen
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import dev.herdroid.app
import dev.herdroid.connection.AgentAlert
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.PaneLayoutRect
import dev.herdroid.herdr.PaneLayoutSnapshot
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.RemoteCommand
import dev.herdroid.terminal.TerminalView
import dev.herdroid.terminal.TerminalViewListener
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.components.QuickReply
import dev.herdroid.ui.components.ConfirmDialog
import dev.herdroid.ui.components.SnapshotBanner
import dev.herdroid.ui.components.ConnectionBanner
import dev.herdroid.ui.components.ConnectionPrompts
import dev.herdroid.ui.components.FieldSpec
import dev.herdroid.ui.components.FormDialog
import dev.herdroid.ui.components.ImageAttachments
import dev.herdroid.ui.components.StatusPill
import dev.herdroid.ui.components.fadingEdges
import dev.herdroid.ui.session.SheetItem
import dev.herdroid.ui.theme.WoolooMobileTheme
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
    val snapshotHealth by connection.snapshotHealth.collectAsState()
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
    val claudeAgent = listOf(agent?.agent, agent?.displayAgent, pane?.agentName).any { it?.contains("claude", ignoreCase = true) == true }
    val blocked = agent?.agentStatus == AgentStatus.BLOCKED
    // The screen only shows the active prompt while scrolled to the bottom.
    val detectOptions = (pane?.scroll?.offsetFromBottom ?: 0L) == 0L && (claudeAgent || blocked)
    val paneRect = pane?.let { p -> paneRectIn(snapshot?.layout(p.tabId), p.paneId) }
    LaunchedEffect(controller, paneRect) { controller.setPaneSize(paneRect?.width, paneRect?.height) }
    var fitScreen by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }

    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var composer by remember { mutableStateOf(false) }
    var pickComposerImages by remember { mutableStateOf(false) }
    var readingTerminal by remember(controller) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var longPress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val retainInputFocus = lifecycleState == Lifecycle.State.RESUMED &&
        controller.state is PaneState.Live && !controller.readOnly &&
        !composer && !readingTerminal && !menu && longPress == null && !renaming && !closing && prompt == null

    // Keep the screen awake while watching a pane, if enabled.
    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Surface other agents that need attention without leaving this pane.
    LaunchedEffect(connection) {
        presentAlerts(
            connection.alerts,
            banner = { alert -> alertBanner(alert, paneId, connection.snapshot.value?.pane(paneId)?.workspaceId) },
            // A banner that waited may be about the pane opened meanwhile (Open on the blocked one).
            stillWanted = { it.paneId != paneId },
        ) { banner ->
            val result = snackbar.showSnackbar(
                message = banner.message,
                actionLabel = "Open",
                withDismissAction = true,
                duration = if (banner.brief) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Same space: switch in place. Another space: go through it, so back leads there.
                if (banner.elsewhere) onOpenInSpace(banner.workspaceId, banner.paneId)
                else paneId = banner.paneId
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
    LaunchedEffect(snapshot, paneId, status, snapshotHealth.stale) {
        val snap = snapshot ?: return@LaunchedEffect
        if (snap.pane(paneId) != null) {
            paneSeen = true
        } else if (paneSeen && status == ConnectionStatus.Connected && !snapshotHealth.stale) {
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
                metrics = { PaneMetrics(connection, agent?.agent ?: pane?.agent) },
                onBack = onBack,
                menu = menu,
                onMenu = { menu = it },
                readOnly = controller.readOnly,
                onToggleReadOnly = { controller.toggleWatching() },
                fitScreen = fitScreenMenu(controller.readOnly && controller.oversizeFrame, fitScreen),
                onFitScreen = { fitScreen = !fitScreen },
                onFont = { delta ->
                    val next = (fontSize + delta).coerceIn(TerminalView.MIN_FONT_SP, TerminalView.MAX_FONT_SP)
                    app.preferences.setFontSize(next)
                    terminalView?.setFontSize(next)
                },
                onCopy = {
                    clipboard.setText(AnnotatedString(controller.model.screenText()))
                    Toast.makeText(context, "Screen copied", Toast.LENGTH_SHORT).show()
                },
                onRead = { readingTerminal = true },
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
            if (status == ConnectionStatus.Connected) {
                SnapshotBanner(snapshotHealth, onRetry = { connection.requestRefresh() })
            }
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
                        view.panWide = controller.readOnly
                        view.fitScreen = fitScreen
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
                            override fun onReadScreen() { readingTerminal = true }
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
                PaneOverlay(recoveryUi(status, controller.state), onTakeOver = { controller.open(takeover = true) },
                    onWatch = { controller.open(observeOnly = true) },
                    onRetry = { controller.open() },
                    onBack = onBack)
                // Over the top rows of the terminal: the switcher, quick answers, extra keys and the
                // prompt at the bottom of the pane stay visible and usable.
                SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { data ->
                    Snackbar(data, shape = RoundedCornerShape(14.dp))
                }
            }
            val prompt = if (detectOptions) controller.promptOptions else PromptOptions()
            val options = prompt.options
            val claudeReview = claudeAgent && isSubmitMenu(options)
            if (controller.state == PaneState.Live && !controller.readOnly && (claudeReview || blocked)) {
                QuickAnswers(options.ifEmpty { FallbackAnswers }, prompt.advance.takeIf { options.isNotEmpty() },
                    fromScreen = options.isNotEmpty(), onAnswer = { terminalView?.sendRaw(it) })
            }
            if (controller.state == PaneState.Live && controller.readOnly) {
                WatchingBar(controller.controlMoved, onTakeControl = { controller.takeControl() })
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
                onComposer = { pickComposerImages = false; composer = true },
                onAttachImage = { pickComposerImages = true; composer = true },
                composerHighlighted = agent?.agentStatus == AgentStatus.BLOCKED,
            )
        }
    }

    ConnectionPrompts(connection, prompt)

    if (readingTerminal) {
        TerminalTextDialog(
            model = controller.model,
            loadHistory = { connection.action { readPane(paneId) } },
            onDismiss = { readingTerminal = false },
        )
    }

    if (composer) {
        val isAgent = agent != null
        ComposerSheet(
            title = if (isAgent) "Message ${agent!!.displayName}" else "Send to ${pane?.displayTitle ?: paneId}",
            agentStatus = agent?.agentStatus,
            isAgent = isAgent,
            onDismiss = { composer = false },
            pickImagesOnOpen = pickComposerImages,
            answers = (if (detectOptions) controller.promptOptions.options else emptyList()).map { it.asQuickReply() },
            watchOnly = controller.readOnly,
            canTakeControl = controller.state == PaneState.Live,
            onTakeControl = { controller.takeControl() },
            onQuickReply = { reply -> controller.input(reply.input) },
            onSend = { text, images, uploads ->
                when {
                    isAgent -> connection.action {
                        promptAgent(paneId, ImageAttachments.withPaths(text, ImageAttachments.upload(connection, images, uploads)))
                        Unit
                    }
                    else -> connection.action {
                        check(controller.state == PaneState.Live && !controller.readOnly) { "Terminal is not accepting input" }
                        val paths = ImageAttachments.upload(connection, images, uploads).joinToString(" ") { RemoteCommand.shellQuote(it) }
                        val input = if (images.isEmpty()) text.replace("\n", "\r") + "\r"
                        else listOf(text.trim(), paths).filter { it.isNotEmpty() }.joinToString(" ").replace("\n", "\r") + "\r"
                        check(controller.tryInput(input)) { "Terminal is not accepting input" }
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
    fitScreen: Boolean? = null,
    onFitScreen: () -> Unit = {},
    metrics: @Composable () -> Unit = {},
    onRead: () -> Unit = {},
) {
    val expanded = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 360
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            // Swipe the title sideways to move between this space's agents.
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
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
                    pane?.let { snapshot?.workspace(it.workspaceId) }?.let { it.label.ifBlank { "Space ${it.number}" } }
                        ?: agentName ?: pane?.displayTitle ?: "Terminal",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (agentStatus != null && agentLabel != null) StatusPill(agentStatus, agentLabel)
            }
            if (!expanded) metrics()
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
                    MenuItem(Icons.Outlined.Terminal, "Read terminal text") { onMenu(false); onRead() }
                    MenuItem(Icons.Outlined.ContentCopy, "Copy screen") { onMenu(false); onCopy() }
                    MenuItem(if (readOnly) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (readOnly) "Take control" else "Watch only") { onMenu(false); onToggleReadOnly() }
                    if (fitScreen != null) {
                        MenuItem(Icons.Outlined.FitScreen, if (fitScreen) "Actual size" else "Fit to screen") {
                            onMenu(false); onFitScreen()
                        }
                    }
                    MenuItem(Icons.Outlined.CropFree, "Toggle zoom") { onMenu(false); onZoom() }
                    MenuItem(Icons.Outlined.ViewColumn, "Split right") { onMenu(false); onSplit("right") }
                    MenuItem(Icons.Outlined.ViewAgenda, "Split down") { onMenu(false); onSplit("down") }
                    MenuItem(Icons.Outlined.Edit, "Rename pane") { onMenu(false); onRename() }
                    MenuItem(Icons.Outlined.Refresh, "Reconnect pane") { onMenu(false); onReconnect() }
                    MenuItem(Icons.Outlined.Close, "Close pane") { onMenu(false); onClosePane() }
                }
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                metrics()
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** Shorten only a whole space prefix, keeping the original label when no suffix remains. */
internal fun paneSwitcherLabel(label: String, space: String): String {
    val prefix = space.trim()
    if (prefix.isEmpty() || !label.startsWith(prefix, ignoreCase = true)) return label
    val rest = label.drop(prefix.length)
    val suffix = rest.dropWhile { it.isWhitespace() || it in "-_/:·›–—." }
    return if (suffix.isNotEmpty() && suffix.length < rest.length) suffix else label
}

/**
 * Every pane of the current space, agents and ordinary terminals alike, in layout order: herdr's
 * state mark for agents, a terminal glyph for shells. The current pane is highlighted. With more
 * than one tab, each tab's panes follow its label, as on the space screen.
 */
@Composable
internal fun PaneSwitcher(snapshot: SessionSnapshot, current: PaneInfo, onSelect: (String) -> Unit) {
    // Only this space's panes: changing space means going back up to the spaces list.
    val groups = snapshot.tabGroupsIn(current.workspaceId).filter { it.panes.isNotEmpty() }
    if (groups.isEmpty()) return
    val labelled = groups.size > 1
    // The item to scroll to for each pane, counting the tab labels before it. A tab's first pane
    // scrolls to its label, so the current pane's tab stays readable.
    val scrollTarget = remember(groups, labelled) {
        buildMap {
            var index = 0
            groups.forEach { group ->
                val label = if (labelled) index++ else null
                group.panes.forEachIndexed { i, pane -> put(pane.paneId, if (i == 0 && label != null) label else index); index++ }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(current.paneId) {
        scrollTarget[current.paneId]?.let { listState.animateScrollToItem(it) }
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fadingEdges(listState),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.forEachIndexed { groupIndex, group ->
            if (labelled) {
                item(key = "tab-" + group.tab.tabId) { SwitcherTabLabel(paneSwitcherLabel(group.tab.displayLabel, snapshot.workspace(current.workspaceId)?.label.orEmpty()), divider = groupIndex > 0) }
            }
            items(group.panes, key = { it.paneId }) { pane ->
                SwitcherChip(snapshot, pane, selected = pane.paneId == current.paneId, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun SwitcherTabLabel(label: String, divider: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (divider) VerticalDivider(Modifier.height(24.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

@Composable
private fun SwitcherChip(snapshot: SessionSnapshot, pane: PaneInfo, selected: Boolean, onSelect: (String) -> Unit) {
    val agent = snapshot.agentFor(pane.paneId)
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
            paneSwitcherLabel(agent?.displayName ?: pane.displayTitle, snapshot.workspace(pane.workspaceId)?.label.orEmpty()),
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

/**
 * Non-modal note while watching a pane, above the extra keys: who has control and what taking it
 * does. Shown both when another client took control and when the user chose to watch.
 */
@Composable
internal fun WatchingBar(controlMoved: Boolean, onTakeControl: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
    ) {
        Row(
            Modifier
                // Announced when control changes hands.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Visibility, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    if (controlMoved) "Another screen took control" else "Watching only",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    TAKE_CONTROL_RESIZES,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onTakeControl) { Text("Take control") }
        }
    }
}

// herdr sizes a pane to its controlling client: control opens with this view's --cols/--rows and
// its resizes apply, while watchers see the pane at the controller's size.
private const val TAKE_CONTROL_RESIZES = "Taking control resizes the pane to fit this phone."

/** The pane menu's Fit to screen state: null (hidden) unless the remote screen is wider than the view. */
internal fun fitScreenMenu(oversizeFrame: Boolean, fitScreen: Boolean): Boolean? = fitScreen.takeIf { oversizeFrame }

/**
 * The area [paneId] covers in [layout], in cells. herdr's layout snapshot keeps the tiled rects
 * while a tab is zoomed, but draws its focused pane over the whole tab [PaneLayoutSnapshot.area]
 * (ui/panes.rs, compute_pane_infos_for_tab), so that pane gets the area; with no area, the
 * bounding box of all the panes. Rects include herdr's pane borders and scrollbar gutter, so they
 * can be a cell or two larger than the terminal: a watcher asks for slightly more, never less.
 */
internal fun paneRectIn(layout: PaneLayoutSnapshot?, paneId: String): PaneLayoutRect? {
    layout ?: return null
    val own = layout.panes.firstOrNull { it.paneId == paneId }?.rect ?: return null
    if (!layout.zoomed || layout.focusedPaneId != paneId) return own
    if (layout.area.width > 0 && layout.area.height > 0) return layout.area
    val rects = layout.panes.map { it.rect }
    val left = rects.minOf { it.x }
    val top = rects.minOf { it.y }
    return PaneLayoutRect(left, top, rects.maxOf { it.x + it.width } - left, rects.maxOf { it.y + it.height } - top)
}

/** A composer chip for [this] option: its number and label, sending the number. */
internal fun PromptOption.asQuickReply() =
    QuickReply(if (label.isEmpty()) "$number" else "$number $label", number.toString())

/** What an alert about another agent shows over the terminal. */
internal data class AlertBanner(
    val message: String,
    val workspaceId: String,
    val paneId: String,
    val elsewhere: Boolean,
    val blocked: Boolean,
) {
    /** An agent finishing in another space only needs a glance: a short banner. */
    val brief: Boolean get() = elsewhere && !blocked
}

/**
 * The banner for [alert] while [currentPaneId] (in [currentWorkspaceId]) is open, or null for this
 * pane's own alerts. Agents from any space show: the system notification is skipped while the app
 * is in the foreground, so the banner is the only notice.
 */
internal fun alertBanner(alert: AgentAlert, currentPaneId: String, currentWorkspaceId: String?): AlertBanner? {
    val agent = alert.agent
    if (agent.paneId == currentPaneId) return null
    val blocked = agent.agentStatus == AgentStatus.BLOCKED
    val elsewhere = agent.workspaceId != currentWorkspaceId
    val where = alert.workspaceLabel?.takeIf { elsewhere }?.let { " in $it" }.orEmpty()
    val verb = if (blocked) "needs input" else "finished"
    return AlertBanner("${agent.displayName}$where $verb", agent.workspaceId, agent.paneId, elsewhere, blocked)
}

/**
 * Shows each alert worth a [banner] with [show]; a newer one cancels the one on screen. Ignored
 * alerts leave the current banner alone. An agent finishing never hides one that needs input: it
 * waits until that banner is gone, then shows if still [stillWanted] (only the latest one waits).
 */
internal suspend fun presentAlerts(
    alerts: Flow<AgentAlert>,
    banner: (AgentAlert) -> AlertBanner?,
    stillWanted: (AlertBanner) -> Boolean = { true },
    show: suspend (AlertBanner) -> Unit,
): Unit = coroutineScope {
    var current: Job? = null
    var currentBlocked = false
    var waiting: AlertBanner? = null
    alerts.mapNotNull(banner).collect { next ->
        if (current?.isActive == true && currentBlocked && !next.blocked) {
            waiting = next
            return@collect
        }
        current?.cancelAndJoin()
        // A finished banner shown now makes an older waiting one stale.
        if (!next.blocked) waiting = null
        // Set now, not when the job runs: an alert in the same burst must see this one as shown.
        currentBlocked = next.blocked
        current = launch {
            var shown: AlertBanner? = next
            while (shown != null) {
                currentBlocked = shown.blocked
                show(shown)
                shown = waiting.also { waiting = null }?.takeIf(stillWanted)
            }
        }
    }
}

/**
 * One-tap answers for a blocked agent's numbered prompt, above the extra keys (which have esc).
 * Each button sends its option's number; the focused option is filled. A multi-select question's
 * checkbox items are toggles (the number toggles that item; the next frame shows the result), and
 * its [advance] row ("Next"/"Submit") is the last button. They share the width when they fit and
 * scroll sideways when they don't. [onAnswer] gets the exact keys to send, without modifiers.
 */
@Composable
internal fun QuickAnswers(
    options: List<PromptOption> = FallbackAnswers,
    advance: AdvanceRow? = null,
    // Only options read from the screen are highlighted there; the fallback's filled 1 is just a default.
    fromScreen: Boolean = options !== FallbackAnswers,
    onAnswer: (String) -> Unit,
) {
    val blocked = LocalStatusColors.current.blocked
    val onSolid = LocalStatusColors.current.onSolid
    val shape = RoundedCornerShape(12.dp)
    val spacing = 6.dp
    val prompt = PromptOptions(options, advance)
    val advanceInput = prompt.advanceInput
    // Whether a Next was sent for this prompt: arrows sent twice would walk past the row, so a
    // second tap waits for the screen to change (any other prompt, even one seen before, resets it).
    var advanceSent by remember(prompt) { mutableStateOf(false) }
    val count = options.size + if (advanceInput != null) 1 else 0
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
        val fits = count * QuickAnswerMinWidth.value + (count - 1) * spacing.value <= maxWidth.value
        val scroll = rememberScrollState()
        Row(
            if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            @Composable
            fun answer(solid: Boolean, action: Modifier, content: @Composable (Color) -> Unit) = Row(
                (if (fits) Modifier.weight(1f) else Modifier.widthIn(min = QuickAnswerMinWidth, max = 168.dp))
                    .height(44.dp)
                    .clip(shape)
                    .then(if (solid) Modifier.background(blocked) else Modifier.border(1.dp, blocked, shape))
                    .then(action)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { content(if (solid) onSolid else MaterialTheme.colorScheme.onSurface) }

            options.forEach { option ->
                val solid = option.focused
                val checked = option.checked
                val highlight = if (fromScreen) Modifier.semantics {
                    selected = solid
                    if (solid) stateDescription = when (checked) {
                        null -> "Highlighted in the prompt"
                        true -> "Checked, highlighted in the prompt"
                        false -> "Not checked, highlighted in the prompt"
                    }
                } else Modifier
                val action = if (checked == null) {
                    highlight.clickable(onClickLabel = "Answer ${option.number}", role = Role.Button) {
                        onAnswer(option.number.toString())
                    }
                } else {
                    // Not flipped here: the remote prompt toggles the item and the next frame shows it.
                    highlight.toggleable(value = checked, role = Role.Checkbox) { onAnswer(option.number.toString()) }
                }
                answer(solid, action) { color ->
                    if (checked != null) {
                        Icon(
                            if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.padding(end = 4.dp).size(18.dp),
                        )
                    }
                    Text(
                        option.number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    if (option.label.isNotEmpty()) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (solid) FontWeight.Bold else FontWeight.Medium,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            if (advance != null && advanceInput != null) {
                val solid = advance.focused
                val clickLabel = if (advance.label == "Submit") "Submit" else "Next question"
                val action = Modifier
                    .then(if (fromScreen) Modifier.semantics {
                        selected = solid
                        if (solid) stateDescription = "Highlighted in the prompt"
                    } else Modifier)
                    .clickable(onClickLabel = clickLabel, role = Role.Button) {
                        if (!advanceSent) {
                            advanceSent = true
                            onAnswer(advanceInput)
                        }
                    }
                answer(solid, action) { color ->
                    Text(
                        advance.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val QuickAnswerMinWidth = 84.dp

@Composable
private fun PaneOverlay(
    recovery: PaneRecovery,
    onTakeOver: () -> Unit,
    onWatch: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    when (recovery) {
        PaneRecovery.None -> return
        PaneRecovery.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        }
        is PaneRecovery.WaitingForHost, is PaneRecovery.Actions -> Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)).clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(24.dp)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val state = (recovery as? PaneRecovery.Actions)?.state
                    val (title, body) = when {
                        // The banner above has the host's actions; this pane only waits for it.
                        recovery is PaneRecovery.WaitingForHost -> if (recovery.failed) {
                            "Not connected" to "This pane reopens once the connection is back."
                        } else {
                            "Offline" to "Waiting for the connection to come back…"
                        }
                        state is PaneState.Busy -> if (state.afterReconnect) {
                            "Confirm control after reconnecting" to
                                "The previous connection may still hold this pane, or another client may control it. Take control here only if you want to replace that controller, or watch without changing it."
                        } else {
                            "Pane in use" to "Another client is controlling this pane. Take control here, or just watch. $TAKE_CONTROL_RESIZES"
                        }
                        state is PaneState.Closed -> "Stream ended" to (state.reason ?: "The pane stream closed.")
                        else -> "" to ""
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    when {
                        recovery is PaneRecovery.WaitingForHost -> if (!recovery.failed) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        state is PaneState.Busy -> {
                            Button(onClick = onTakeOver, modifier = Modifier.fillMaxWidth()) { Text("Take control") }
                            OutlinedButton(onClick = onWatch, modifier = Modifier.fillMaxWidth()) { Text("Watch only") }
                        }
                        state is PaneState.Closed -> {
                            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to session") }
                        }
                    }
                }
            }
        }
    }
}

private const val SWIPE_PX = 120f
