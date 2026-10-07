package dev.herdroid.ui.hosts

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostLink
import dev.herdroid.data.HostProfile
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyInfo
import dev.herdroid.data.Transport
import dev.herdroid.ui.theme.Mono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: String?,
    onDone: (savedId: String?, connect: Boolean) -> Unit,
    onImportKey: () -> Unit,
    link: String? = null,
) {
    val context = LocalContext.current
    val app = context.app
    val existing = remember(hostId) { hostId?.let { app.hosts.get(it) } }
    val keys by app.keys.keys.collectAsState()
    // A herdroid://add-host link prefills the machine; the user still chooses how to sign in.
    val imported = remember(link) { link?.let(HostLink::parse) }
    val initial = remember(hostId, link) {
        val base = existing ?: HostProfile()
        imported?.applyTo(base) ?: base
    }

    var label by rememberSaveable { mutableStateOf(initial.label) }
    var transport by rememberSaveable { mutableStateOf(initial.transport) }
    var hostname by rememberSaveable { mutableStateOf(initial.hostname) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var accessClientId by rememberSaveable { mutableStateOf(initial.accessClientId.orEmpty()) }
    var accessSecret by rememberSaveable { mutableStateOf("") }
    // Joined with newlines: a plain String is always saveable.
    var pinnedKeys by rememberSaveable { mutableStateOf(initial.hostKeyFingerprints.joinToString("\n")) }
    var auth by rememberSaveable { mutableStateOf(initial.auth) }
    var keyId by rememberSaveable { mutableStateOf(existing?.keyId ?: keys.lastOrNull()?.id) }
    var password by rememberSaveable { mutableStateOf("") }
    var session by rememberSaveable { mutableStateOf(initial.session) }
    var herdrPath by rememberSaveable { mutableStateOf(initial.herdrPath.orEmpty()) }
    var notifyBlocked by rememberSaveable { mutableStateOf(initial.notifyWhenBlocked) }
    var notifyDone by rememberSaveable { mutableStateOf(initial.notifyWhenDone) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    val hasSavedPassword = remember(hostId) {
        hostId != null && app.secrets.get(SecretStore.passwordKey(hostId)) != null
    }
    val hasSavedAccessSecret = remember(hostId) {
        hostId != null && app.secrets.get(SecretStore.accessSecretKey(hostId)) != null
    }
    val tunnel = transport == Transport.CLOUDFLARE

    // Accept "user@host:port" pasted into the host field, or a tunnel's https:// URL.
    fun splitTarget() {
        if (tunnel) {
            hostname = HostLink.normalizeHostname(hostname)
            return
        }
        var h = hostname.trim().removePrefix("ssh://")
        if ('@' in h) {
            if (username.isBlank()) username = h.substringBefore('@')
            h = h.substringAfter('@')
        }
        if (h.count { it == ':' } == 1) {
            h.substringAfter(':').toIntOrNull()?.let { port = it.toString() }
            h = h.substringBefore(':')
        }
        hostname = h
    }

    val portValue = port.toIntOrNull()
    val hostError = hostname.isBlank()
    val userError = username.isBlank()
    val portError = !tunnel && (portValue == null || portValue !in 1..65535)
    val keyError = auth == AuthMethod.KEY && keyId == null
    val valid = !hostError && !userError && !portError && !keyError

    fun save(connect: Boolean) {
        splitTarget()
        if (!valid) { showErrors = true; return }
        val profile = (existing ?: HostProfile()).copy(
            label = label.trim(),
            hostname = hostname.trim(),
            port = if (tunnel) initial.port else portValue ?: 22,
            username = username.trim(),
            transport = transport,
            accessClientId = accessClientId.trim().takeIf { tunnel && it.isNotEmpty() },
            hostKeyFingerprints = pinnedKeys.lines().filter { it.isNotBlank() },
            auth = auth,
            keyId = keyId,
            session = session.trim().ifEmpty { "default" },
            herdrPath = herdrPath.trim().ifEmpty { null },
            notifyWhenBlocked = notifyBlocked,
            notifyWhenDone = notifyDone,
        )
        app.hosts.upsert(profile)
        if (auth == AuthMethod.PASSWORD && password.isNotEmpty()) {
            app.secrets.put(SecretStore.passwordKey(profile.id), password)
        }
        if (auth == AuthMethod.KEY) app.secrets.remove(SecretStore.passwordKey(profile.id))
        if (profile.accessClientId == null) {
            app.secrets.remove(SecretStore.accessSecretKey(profile.id))
        } else if (accessSecret.isNotEmpty()) {
            app.secrets.put(SecretStore.accessSecretKey(profile.id), accessSecret)
        }
        app.connections.profileChanged(profile.id)
        onDone(profile.id, connect)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add machine" else "Edit machine") },
                navigationIcon = {
                    IconButton(onClick = { onDone(null, false) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = { TextButton(onClick = { save(connect = false) }) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            imported?.let { ImportNotice(updating = existing?.displayName) }

            SectionTitle("Machine")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Transport.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = transport == option,
                        onClick = { transport = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Transport.entries.size),
                    ) { Text(if (option == Transport.SSH) "SSH" else "Cloudflare Tunnel") }
                }
            }
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text(if (tunnel) "Tunnel hostname" else "Host") },
                placeholder = { Text(if (tunnel) "name.trycloudflare.com" else "devbox.local or user@203.0.113.7") },
                singleLine = true,
                isError = showErrors && hostError,
                supportingText = {
                    Text(
                        if (tunnel) "The tunnel's public hostname; a pasted https:// URL is fine"
                        else "Tip: paste user@host:port and it's split for you"
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) splitTarget() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("User") },
                    singleLine = true,
                    isError = showErrors && userError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                if (!tunnel) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = showErrors && portError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.width(100.dp),
                    )
                }
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Name (optional)") },
                placeholder = { Text(hostname.ifBlank { "Build box" }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (tunnel) {
                SectionTitle("Cloudflare Access (optional)")
                OutlinedTextField(
                    value = accessClientId,
                    onValueChange = { accessClientId = it.trim() },
                    label = { Text("Service token client ID") },
                    singleLine = true,
                    supportingText = { Text("Only if the hostname is protected by Cloudflare Access.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (accessClientId.isNotBlank()) {
                    OutlinedTextField(
                        value = accessSecret,
                        onValueChange = { accessSecret = it.trim() },
                        label = {
                            Text(if (hasSavedAccessSecret) "Client secret (saved — leave empty to keep)" else "Client secret")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text("Stored encrypted with the Android Keystore.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionTitle("Sign in with")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMethod.entries.forEachIndexed { index, method ->
                    SegmentedButton(
                        selected = auth == method,
                        onClick = { auth = method },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                    ) { Text(if (method == AuthMethod.KEY) "SSH key" else "Password") }
                }
            }

            if (auth == AuthMethod.KEY) {
                KeyPicker(
                    keys = keys,
                    selectedId = keyId,
                    isError = showErrors && keyError,
                    onSelect = { keyId = it },
                    onGenerate = {
                        val info = app.keys.generateEd25519("herdroid@${android.os.Build.MODEL.replace(' ', '-')}")
                        keyId = info.id
                    },
                    onImport = onImportKey,
                )
                keys.firstOrNull { it.id == keyId }?.let { PublicKeyCard(it) }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (hasSavedPassword) "Password (saved — leave empty to keep)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("Stored encrypted with the Android Keystore. Leave empty to be asked each time.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionTitle("herdr")
            OutlinedTextField(
                value = session,
                onValueChange = { session = it.trim() },
                label = { Text("Session") },
                singleLine = true,
                supportingText = { Text("Matches `herdr --session <name>`. Most setups use \"default\".") },
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text(" Advanced")
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = herdrPath,
                        onValueChange = { herdrPath = it },
                        label = { Text("herdr executable") },
                        placeholder = { Text("auto-detect (~/.local/bin/herdr, PATH, …)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pinnedKeys.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Trusted host keys", style = MaterialTheme.typography.bodyMedium)
                                pinnedKeys.lines().forEach {
                                    Text(it, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { pinnedKeys = "" }) { Text("Forget") }
                        }
                    }
                    ToggleRow("Notify when an agent needs input", notifyBlocked) { notifyBlocked = it }
                    ToggleRow("Notify when an agent finishes", notifyDone) { notifyDone = it }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { save(connect = true) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save & connect") }
            if (showErrors && !valid) {
                Text(
                    when {
                        hostError && tunnel -> "Enter the tunnel's hostname."
                        hostError -> "Enter the machine's hostname or IP."
                        userError -> "Enter the SSH user."
                        portError -> "Port must be between 1 and 65535."
                        else -> "Pick or generate an SSH key."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

/** Shown when the editor was opened from a herdroid://add-host link. */
@Composable
private fun ImportNotice(updating: String?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            if (updating != null) {
                "Updating $updating from a link: new address and host keys. Check them, then save."
            } else {
                "Machine details from a link. Check them, choose how to sign in, then save."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPicker(
    keys: List<SshKeyInfo>,
    selectedId: String?,
    isError: Boolean,
    onSelect: (String) -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = keys.firstOrNull { it.id == selectedId }
    if (keys.isNotEmpty()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.let { "${it.name} · ${it.type}" } ?: "Choose a key",
                onValueChange = {},
                readOnly = true,
                isError = isError,
                label = { Text("Key") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                keys.forEach { key ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(key.name)
                                Text(key.fingerprint, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { onSelect(key.id); expanded = false },
                    )
                }
            }
        }
    }
    // Short single-line labels: at large font sizes "Generate new" wrapped inside half the width.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onGenerate, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New key", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (isError && keys.isEmpty()) {
        Text(
            "Generate a key here, then add it to the machine's ~/.ssh/authorized_keys.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Shows the public key with copy/share and the exact command to authorize it. */
@Composable
fun PublicKeyCard(key: SshKeyInfo) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val command = "mkdir -p ~/.ssh && echo '${key.publicKey}' >> ~/.ssh/authorized_keys"
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Authorize this key on the machine", style = MaterialTheme.typography.titleSmall)
            Text(
                "Run this once in a terminal on the machine (or append the key to ~/.ssh/authorized_keys):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Shown whole and selectable: it's what the user pastes on the machine, so it must be checkable.
            SelectionContainer {
                Text(command, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(command))
                    Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Text(" Copy command")
                }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, key.publicKey)
                    }
                    context.startActivity(Intent.createChooser(send, "Share public key"))
                }) {
                    Icon(Icons.Outlined.Share, null)
                    Text(" Share key")
                }
            }
        }
    }
}
