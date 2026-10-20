package dev.herdroid.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdroid.terminal.TerminalModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Explicit snapshots keep screen readers stable while the live terminal continues to repaint. */
@Composable
internal fun TerminalTextDialog(
    model: TerminalModel,
    loadHistory: suspend () -> Result<String>,
    onDismiss: () -> Unit,
) {
    var text by remember(model) { mutableStateOf(model.screenText().trimEnd()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Terminal text", style = MaterialTheme.typography.titleLarge)
                Text("Snapshot; refresh when you want to read new output.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !loading, onClick = {
                    text = model.screenText().trimEnd()
                    error = null
                }) { Text("Refresh screen") }
                TextButton(enabled = !loading, onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            loadHistory().getOrThrow().let { text = it }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not read recent output" }
                        finally { loading = false }
                    }
                }) { Text(if (loading) "Loading…" else "Read recent output (400 lines)") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(text.ifBlank { "No terminal text yet." })
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
