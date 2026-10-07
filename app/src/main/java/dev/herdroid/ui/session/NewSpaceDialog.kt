package dev.herdroid.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.DirListing

/**
 * "New space": a name plus a starting directory, which can be typed, taken from a directory
 * other panes already use, or browsed on the host. Picking a directory names the space after it
 * unless the user typed a name.
 */
@Composable
internal fun NewSpaceDialog(
    connection: HerdrConnection,
    onDismiss: () -> Unit,
    onCreate: (label: String, cwd: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var dir by rememberSaveable { mutableStateOf("") }
    var nameIsAuto by rememberSaveable { mutableStateOf(true) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val snapshot by connection.snapshot.collectAsState()
    val recent = remember(snapshot) {
        snapshot?.panes.orEmpty()
            .mapNotNull { (it.foregroundCwd ?: it.cwd)?.trimEnd('/')?.takeIf { p -> p.isNotEmpty() } }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .map { connection.abbreviatePath(it.key) }
            .filter { it != "~" }
            .take(6)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val defaultName = dirName(dir)

    fun choose(path: String) {
        dir = path
        if (nameIsAuto || name.isBlank()) {
            name = dirName(path)
            nameIsAuto = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New space") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dir,
                    onValueChange = { choose(it) },
                    label = { Text("Directory") },
                    placeholder = { Text("~/project (optional)") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { browsing = true }, enabled = connection.isLive) {
                            Icon(Icons.Outlined.FolderOpen, "Browse directories")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (recent.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        recent.forEach { path ->
                            AssistChip(
                                onClick = { choose(path) },
                                label = { Text(path.substringAfterLast('/'), maxLines = 1) },
                                leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameIsAuto = it.isBlank() },
                    label = { Text("Name (optional)") },
                    placeholder = { Text(defaultName.ifEmpty { "Directory name" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.trim().ifEmpty { defaultName }, dir.trim().takeIf { it.isNotEmpty() }) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (browsing) {
        DirectoryPickerSheet(
            connection = connection,
            start = dir.trim().ifEmpty { "~" },
            onDismiss = { browsing = false },
            onPick = { browsing = false; choose(connection.abbreviatePath(it)) },
        )
    }
}

/** Browses the host's directories over SSH, one level at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    connection: HerdrConnection,
    start: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var target by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(target) {
        loading = true
        connection.listDirectories(target)
            .onSuccess { listing = it; error = null; filter = "" }
            .onFailure { error = it.message ?: "Couldn't list directory" }
        loading = false
    }

    val current = listing
    val entries = current?.entries.orEmpty().filter {
        (showHidden || !it.hidden || filter.startsWith(".")) && it.name.contains(filter, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.9f).imePadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { current?.parent?.let { target = it } }, enabled = current?.parent != null) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Parent directory")
                }
                Text(
                    current?.path?.let(connection::abbreviatePath) ?: start,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { target = "~" }) { Icon(Icons.Outlined.Home, "Home directory") }
            }
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Filter") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Hidden") })
            }
            Box(Modifier.height(4.dp).fillMaxWidth()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f)) {
                when {
                    error != null && current == null -> Message(error!!)
                    current != null && entries.isEmpty() && !loading ->
                        Message(if (filter.isEmpty()) "No subdirectories" else "Nothing matches \"$filter\"")
                    else -> LazyColumn {
                        error?.let { item { Message(it) } }
                        items(entries, key = { it.name }) { entry ->
                            ListItem(
                                modifier = Modifier.clickable(enabled = !loading) { target = current!!.child(entry.name) },
                                leadingContent = {
                                    Icon(
                                        if (entry.isRepo) Icons.Outlined.FolderSpecial else Icons.Outlined.Folder,
                                        null,
                                        tint = if (entry.isRepo) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = if (entry.isRepo) {
                                    { Text("git", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                } else null,
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { current?.let { onPick(it.path) } },
                enabled = current != null && !loading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Use ${current?.name ?: "this directory"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `~/src/api/` → `api`; empty for home or nothing. */
private fun dirName(path: String): String =
    path.trim().trimEnd('/').substringAfterLast('/').takeIf { it != "~" }.orEmpty()

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
