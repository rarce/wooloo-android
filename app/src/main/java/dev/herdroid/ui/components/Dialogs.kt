package dev.herdroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

data class FieldSpec(val label: String, val initial: String = "", val placeholder: String = "")

/** A small form dialog: one or more single-line fields and a confirm button. */
@Composable
fun FormDialog(
    title: String,
    fields: List<FieldSpec>,
    confirm: String,
    onDismiss: () -> Unit,
    requireFirst: Boolean = false,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, field ->
                    var value by values[i]
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(field.label) },
                        placeholder = { if (field.placeholder.isNotEmpty()) Text(field.placeholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(focus) else it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !requireFirst || values.first().value.isNotBlank(),
                onClick = { onConfirm(values.map { it.value.trim() }) },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
