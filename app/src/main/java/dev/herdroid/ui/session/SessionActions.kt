package dev.herdroid.ui.session

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.PaneInfo
import dev.herdroid.herdr.TabInfo
import dev.herdroid.herdr.WorkspaceInfo
import dev.herdroid.ui.components.ComposerSheet
import dev.herdroid.ui.components.ConfirmDialog
import dev.herdroid.ui.components.FieldSpec
import dev.herdroid.ui.components.FormDialog
import dev.herdroid.ui.components.ImageAttachments
import kotlinx.coroutines.launch

/** A pending dialog on the spaces or space screen. */
internal sealed interface SessionDialog {
    data object NewSpace : SessionDialog
    data class NewTab(val workspace: WorkspaceInfo) : SessionDialog
    data class RenameSpace(val workspace: WorkspaceInfo) : SessionDialog
    data class CloseSpace(val workspace: WorkspaceInfo) : SessionDialog
    data class RenameTab(val tab: TabInfo) : SessionDialog
    data class CloseTab(val tab: TabInfo) : SessionDialog
    data class RenamePane(val pane: PaneInfo) : SessionDialog
    data class ClosePane(val pane: PaneInfo) : SessionDialog
}

/** Dialogs and sheets the spaces and space screens share, plus how they run herdr actions. */
@Stable
internal class SessionUi(val act: (label: String, block: suspend HerdrApi.() -> Unit) -> Unit) {
    var dialog by mutableStateOf<SessionDialog?>(null)
    var paneSheet by mutableStateOf<PaneInfo?>(null)
    var composeFor by mutableStateOf<AgentInfo?>(null)
}

@Composable
internal fun rememberSessionUi(connection: HerdrConnection): SessionUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(connection) {
        SessionUi { label, block ->
            scope.launch {
                connection.action(block).onFailure {
                    Toast.makeText(context, "$label failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
internal fun SessionOverlays(ui: SessionUi, connection: HerdrConnection, onOpenPane: (String) -> Unit) {
    val act = ui.act
    ui.paneSheet?.let { pane ->
        PaneActionsSheet(
            pane = pane,
            onDismiss = { ui.paneSheet = null },
            onOpen = { ui.paneSheet = null; onOpenPane(pane.paneId) },
            onRename = { ui.paneSheet = null; ui.dialog = SessionDialog.RenamePane(pane) },
            onZoom = { ui.paneSheet = null; act("Zoom") { zoomPane(pane.paneId) } },
            onSplit = { dir -> ui.paneSheet = null; act("Split") { splitPane(pane.paneId, dir) } },
            onClose = { ui.paneSheet = null; ui.dialog = SessionDialog.ClosePane(pane) },
        )
    }

    ui.composeFor?.let { agent ->
        ComposerSheet(
            title = "Message ${agent.displayName}",
            agentStatus = agent.agentStatus,
            isAgent = true,
            onDismiss = { ui.composeFor = null },
            onQuickReply = { reply -> act("Send") { sendText(agent.paneId, reply.input) } },
            onSend = { text, images ->
                act("Prompt") {
                    promptAgent(agent.paneId, ImageAttachments.withPaths(text, ImageAttachments.upload(connection, images)))
                }
            },
        )
    }

    val dismiss = { ui.dialog = null }
    when (val d = ui.dialog) {
        null -> Unit
        SessionDialog.NewSpace -> NewSpaceDialog(connection, onDismiss = dismiss) { label, cwd ->
            dismiss()
            act("Create space") { createWorkspace(label, cwd?.let(connection::expandPath)) }
        }
        is SessionDialog.NewTab -> FormDialog(
            title = "New tab in ${d.workspace.label}",
            fields = listOf(FieldSpec("Name", placeholder = "logs")),
            confirm = "Create",
            onDismiss = dismiss,
        ) { (label) -> dismiss(); act("Create tab") { createTab(d.workspace.workspaceId, label) } }
        is SessionDialog.RenameSpace -> FormDialog(
            title = "Rename space",
            fields = listOf(FieldSpec("Name", d.workspace.label)),
            confirm = "Rename",
            requireFirst = true,
            onDismiss = dismiss,
        ) { (label) -> dismiss(); act("Rename") { renameWorkspace(d.workspace.workspaceId, label) } }
        is SessionDialog.CloseSpace -> ConfirmDialog(
            title = "Close ${d.workspace.label}?",
            body = "Closes ${count(d.workspace.tabCount, "tab")} and ${count(d.workspace.paneCount, "pane")}, stopping their processes.",
            confirm = "Close space",
            onDismiss = dismiss,
        ) { act("Close space") { closeWorkspace(d.workspace.workspaceId) } }
        is SessionDialog.RenameTab -> FormDialog(
            title = "Rename tab",
            fields = listOf(FieldSpec("Name", d.tab.label.takeIf { d.tab.hasCustomLabel }.orEmpty())),
            confirm = "Rename",
            requireFirst = true,
            onDismiss = dismiss,
        ) { (label) -> dismiss(); act("Rename") { renameTab(d.tab.tabId, label) } }
        is SessionDialog.CloseTab -> ConfirmDialog(
            title = "Close ${d.tab.displayLabel}?",
            body = "Closes ${count(d.tab.paneCount, "pane")} and stops their processes.",
            confirm = "Close tab",
            onDismiss = dismiss,
        ) { act("Close tab") { closeTab(d.tab.tabId) } }
        is SessionDialog.RenamePane -> FormDialog(
            title = "Rename pane",
            fields = listOf(FieldSpec("Label", d.pane.label.orEmpty())),
            confirm = "Rename",
            onDismiss = dismiss,
        ) { (label) -> dismiss(); act("Rename") { renamePane(d.pane.paneId, label) } }
        is SessionDialog.ClosePane -> ConfirmDialog(
            title = "Close ${d.pane.displayTitle}?",
            body = "The pane's process will be stopped.",
            confirm = "Close pane",
            onDismiss = dismiss,
        ) { act("Close pane") { closePane(d.pane.paneId) } }
    }
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
fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
