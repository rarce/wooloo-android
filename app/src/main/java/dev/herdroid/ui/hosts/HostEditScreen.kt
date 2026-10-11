package dev.herdroid.ui.hosts

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.herdroid.app
import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostLink
import dev.herdroid.data.HostProfile
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyInfo
import dev.herdroid.data.Transport
import dev.herdroid.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val savedId = rememberSaveable(hostId, link) { initial.id }
    val edits = remember(hostId, link) { BackgroundEdit() }
    BackHandler(enabled = edits.busy) { }

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
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hostFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    val hostIntoView = remember { BringIntoViewRequester() }
    val userIntoView = remember { BringIntoViewRequester() }
    val portIntoView = remember { BringIntoViewRequester() }
    val keyIntoView = remember { BringIntoViewRequester() }

    var hasSavedPassword by remember(hostId) { mutableStateOf(false) }
    var hasSavedAccessSecret by remember(hostId) { mutableStateOf(false) }
    var secretReadError by remember(hostId) { mutableStateOf(false) }
    LaunchedEffect(hostId) {
        if (hostId != null) {
            try {
                val presence = withContext(Dispatchers.IO) {
                    app.secrets.contains(SecretStore.passwordKey(hostId)) to
                        app.secrets.contains(SecretStore.accessSecretKey(hostId))
                }
                hasSavedPassword = presence.first
                hasSavedAccessSecret = presence.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                secretReadError = true
            }
        }
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
    val keyError = auth == AuthMethod.KEY && keys.none { it.id == keyId }

    fun save(connect: Boolean) {
        if (edits.busy) return
        splitTarget()
        // Validate the normalized values now, rather than values from the previous composition.
        val normalizedPort = port.toIntOrNull()
        val invalid = when {
            hostname.isBlank() -> hostFocus to hostIntoView
            username.isBlank() -> userFocus to userIntoView
            !tunnel && (normalizedPort == null || normalizedPort !in 1..65535) -> portFocus to portIntoView
            keyError -> keyFocus to keyIntoView
            else -> null
        }
        if (invalid != null) {
            showErrors = true
            scope.launch {
                withFrameNanos { }
                invalid.first.requestFocus()
                invalid.second.bringIntoView()
            }
            return
        }
        val profile = initial.copy(
            id = savedId,
            label = label.trim(),
            hostname = hostname.trim(),
            port = if (tunnel) initial.port else normalizedPort ?: 22,
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
        val savedPassword = password
        val savedAccessSecret = accessSecret
        val replacements = buildMap {
            if (profile.auth == AuthMethod.PASSWORD && savedPassword.isNotEmpty()) {
                put(SecretStore.passwordKey(profile.id), savedPassword)
            }
            if (profile.accessClientId != null && savedAccessSecret.isNotEmpty()) {
                put(SecretStore.accessSecretKey(profile.id), savedAccessSecret)
            }
        }
        val removals = buildSet {
            if (profile.auth == AuthMethod.KEY) add(SecretStore.passwordKey(profile.id))
            if (profile.accessClientId == null) add(SecretStore.accessSecretKey(profile.id))
        }
        edits.submit(scope, "Could not save this machine. Your changes are still here.", work = {
            app.secrets.transaction(replacements, removals) { app.hosts.upsert(profile) }
        }, success = {
            app.connections.profileChanged(profile.id)
            onDone(profile.id, connect)
        })
    }

    Scaffold(
        snackbarHost = {
            Column(Modifier.padding(16.dp)) {
                if (edits.busy) Text("Working…")
                edits.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add machine" else "Edit machine") },
                navigationIcon = {
                    IconButton(enabled = !edits.busy, onClick = { if (!edits.busy) onDone(null, false) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(enabled = !edits.busy, onClick = { save(connect = imported != null) }) {
                        Text(if (imported != null) "Connect" else "Save")
                    }
                },
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
            if (secretReadError) Text("Saved credentials could not be read. Enter them again to replace them.",
                color = MaterialTheme.colorScheme.error)
            imported?.let { ImportNotice(updating = existing?.displayName) }

            SectionTitle("Machine")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Transport.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        enabled = !edits.busy,
                        selected = transport == option,
                        onClick = { transport = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Transport.entries.size),
                        modifier = Modifier.fillMaxHeight(),
                    ) { Text(if (option == Transport.SSH) "SSH" else "Cloudflare Tunnel", textAlign = TextAlign.Center) }
                }
            }
            OutlinedTextField(
                enabled = !edits.busy,
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text(if (tunnel) "Tunnel hostname" else "Host") },
                placeholder = { Text(if (tunnel) "name.trycloudflare.com" else "devbox.local or user@203.0.113.7") },
                singleLine = true,
                isError = showErrors && hostError,
                supportingText = {
                    Text(
                        if (showErrors && hostError) {
                            if (tunnel) "Enter the tunnel's hostname." else "Enter the machine's hostname or IP."
                        } else if (tunnel) "The tunnel's public hostname; a pasted https:// URL is fine"
                        else "Tip: paste user@host:port and it's split for you"
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(hostFocus).bringIntoViewRequester(hostIntoView)
                    .onFocusChanged { if (!it.isFocused) splitTarget() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    enabled = !edits.busy,
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("User") },
                    singleLine = true,
                    isError = showErrors && userError,
                    supportingText = if (showErrors && userError) ({ Text("Enter the SSH user.") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f).focusRequester(userFocus).bringIntoViewRequester(userIntoView),
                )
                if (!tunnel) {
                    OutlinedTextField(
                        enabled = !edits.busy,
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        isError = showErrors && portError,
                        supportingText = if (showErrors && portError) ({ Text("Use 1–65535.") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.width(100.dp * LocalDensity.current.fontScale.coerceAtMost(1.6f))
                            .focusRequester(portFocus).bringIntoViewRequester(portIntoView),
                    )
                }
            }
            OutlinedTextField(
                enabled = !edits.busy,
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
                    enabled = !edits.busy,
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
                        enabled = !edits.busy,
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                AuthMethod.entries.forEachIndexed { index, method ->
                    SegmentedButton(
                        enabled = !edits.busy,
                        selected = auth == method,
                        onClick = { auth = method },
                        shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                        modifier = Modifier.fillMaxHeight(),
                    ) { Text(if (method == AuthMethod.KEY) "SSH key" else "Password", textAlign = TextAlign.Center) }
                }
            }

            if (auth == AuthMethod.KEY) {
                KeyPicker(
                    enabled = !edits.busy,
                    keys = keys,
                    selectedId = keyId,
                    isError = showErrors && keyError,
                    onSelect = { if (!edits.busy) keyId = it },
                    onGenerate = {
                        edits.submit(scope, "Could not generate a key. Try again.", work = {
                            app.keys.generateEd25519("wooloo-mobile@${android.os.Build.MODEL.replace(' ', '-')}")
                        }, success = { keyId = it.id })
                    },
                    onImport = { if (!edits.busy) onImportKey() },
                    focus = keyFocus,
                    intoView = keyIntoView,
                )
                keys.firstOrNull { it.id == keyId }?.let { PublicKeyCard(it) }
            } else {
                OutlinedTextField(
                    enabled = !edits.busy,
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
                enabled = !edits.busy,
                value = session,
                onValueChange = { session = it.trim() },
                label = { Text("Session") },
                singleLine = true,
                supportingText = { Text("Use the session name from your machine. Usually \"default\".") },
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(enabled = !edits.busy, onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Text(" Advanced")
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        enabled = !edits.busy,
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
                            TextButton(enabled = !edits.busy, onClick = { pinnedKeys = "" }) { Text("Forget") }
                        }
                    }
                    ToggleRow("Notify when an agent needs input", notifyBlocked, enabled = !edits.busy) { notifyBlocked = it }
                    ToggleRow("Notify when an agent finishes", notifyDone, enabled = !edits.busy) { notifyDone = it }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !edits.busy,
                onClick = { save(connect = true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (imported != null) "Connect" else "Save & connect") }
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
                "Updating $updating from Wooloo: new address and host keys. Check them, then connect."
            } else {
                "Connection details from Wooloo. Use a key authorized on this machine or its SSH password, then connect."
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
private fun ToggleRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(enabled = enabled, checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPicker(
    enabled: Boolean,
    keys: List<SshKeyInfo>,
    selectedId: String?,
    isError: Boolean,
    onSelect: (String) -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    focus: FocusRequester,
    intoView: BringIntoViewRequester,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = keys.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(intoView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (keys.isNotEmpty()) {
            ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
                OutlinedTextField(
                    value = selected?.let { "${it.name} · ${it.type}" } ?: "Choose a key",
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    isError = isError,
                    label = { Text("Key") },
                    supportingText = if (isError) ({ Text("Choose an SSH key.") }) else null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
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
            OutlinedButton(enabled = enabled, onClick = onGenerate, modifier = Modifier.weight(1f).then(
                if (keys.isEmpty()) Modifier.focusRequester(focus) else Modifier
            )) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New key", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(enabled = enabled, onClick = onImport, modifier = Modifier.weight(1f)) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
