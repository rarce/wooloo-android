package dev.herdroid.ui.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.data.SshKeyInfo
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(onBack: () -> Unit, startWithImport: Boolean = false) {
    val app = LocalContext.current.app
    val keys by app.keys.keys.collectAsState()
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKeyInfo?>(null) }
    LaunchedEffect(startWithImport) { if (startWithImport) importing = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH keys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { importing = true }) { Icon(Icons.Outlined.FileDownload, "Import key") }
                    IconButton(onClick = {
                        app.keys.generateEd25519("wooloo-mobile@${android.os.Build.MODEL.replace(' ', '-')}")
                    }) { Icon(Icons.Outlined.AutoAwesome, "Generate key") }
                },
            )
        },
    ) { padding ->
        if (keys.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Key,
                title = "No keys yet",
                body = "Generate an Ed25519 key on this phone, or import an existing private key. " +
                    "Private keys are encrypted with the Android Keystore and never leave the device.",
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = { app.keys.generateEd25519("wooloo-mobile@${android.os.Build.MODEL.replace(' ', '-')}") }) {
                    Text("Generate key")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(keys, key = { it.id }) { key ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(key.name, style = MaterialTheme.typography.titleMedium)
                                    Text(key.fingerprint, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { deleting = key }) { Icon(Icons.Outlined.Delete, "Delete key") }
                            }
                            PublicKeyCard(key)
                        }
                    }
                }
            }
        }
    }

    if (importing) ImportKeyDialog(onDismiss = { importing = false })

    deleting?.let { key ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${key.name}?") },
            text = { Text("Machines using this key won't be able to connect until you pick another one.") },
            confirmButton = {
                TextButton(onClick = { app.keys.delete(key.id); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ImportKeyDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pem, { pem = it; error = null },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it },
                    label = { Text("Passphrase (if any)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = pem.isNotBlank(), onClick = {
                runCatching { app.keys.import(name, pem, passphrase.ifEmpty { null }) }
                    .onSuccess { onDismiss() }
                    .onFailure { error = it.message ?: "Could not read that key" }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
