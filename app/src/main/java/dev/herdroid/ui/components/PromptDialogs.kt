package dev.herdroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.connection.PasswordAnswer
import dev.herdroid.connection.UserPrompt
import dev.herdroid.ui.theme.Mono

/** Shows whatever the connection is waiting on the user for (host key, password). */
@Composable
fun ConnectionPrompts(connection: HerdrConnection, prompt: UserPrompt?) {
    when (prompt) {
        is UserPrompt.HostKey -> HostKeyDialog(prompt) { accepted -> connection.answer(prompt, accepted) }
        is UserPrompt.Password -> PasswordDialog(prompt) { answer ->
            connection.answer(prompt, answer != null, answer)
        }
        null -> Unit
    }
}

@Composable
private fun HostKeyDialog(prompt: UserPrompt.HostKey, onResult: (Boolean) -> Unit) {
    val c = prompt.challenge
    val changed = c.previousFingerprint != null
    AlertDialog(
        onDismissRequest = { onResult(false) },
        icon = {
            Icon(
                if (changed) Icons.Outlined.WarningAmber else Icons.Outlined.Key,
                null,
                tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(if (changed) "Host key changed!" else "Trust this machine?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (changed) {
                        "The key for ${c.host} doesn't match the one you trusted before. " +
                            "This can mean the server was reinstalled — or that someone is intercepting the connection."
                    } else {
                        "First connection to ${c.host}:${c.port}. Compare the fingerprint with " +
                            "`ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub` on the machine."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(c.keyType, style = MaterialTheme.typography.labelMedium)
                Text(c.fingerprint, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                if (changed) {
                    Text("Previously trusted:", style = MaterialTheme.typography.labelMedium)
                    Text(c.previousFingerprint!!, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onResult(true) }) { Text(if (changed) "Trust new key" else "Trust & connect") }
        },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(prompt: UserPrompt.Password, onResult: (PasswordAnswer?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text("Password for ${prompt.target}") },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("Remember (encrypted on this device)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = password.isNotEmpty(), onClick = { onResult(PasswordAnswer(password, remember)) }) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}
