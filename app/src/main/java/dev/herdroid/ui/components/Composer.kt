package dev.herdroid.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.herdroid.herdr.AgentStatus

/** A quick reply that is typed straight into the pane (raw terminal input). */
data class QuickReply(val label: String, val input: String)

val AgentQuickReplies = listOf(
    QuickReply("⏎ Enter", "\r"),
    QuickReply("1", "1"),
    QuickReply("2", "2"),
    QuickReply("3", "3"),
    QuickReply("y", "y"),
    QuickReply("n", "n"),
    QuickReply("Esc", "\u001b"),
    QuickReply("⇧Tab", "\u001b[Z"),
    QuickReply("Ctrl‑C", "\u0003"),
)

val ShellQuickReplies = listOf(
    QuickReply("Ctrl‑C", "\u0003"),
    QuickReply("⏎ Enter", "\r"),
    QuickReply("↑ last", "\u001b[A"),
    QuickReply("clear", "clear\r"),
    QuickReply("q", "q"),
)

/**
 * The comfortable way to talk to an agent from a phone: a real multi-line text field with
 * autocorrect, swipe typing and voice input, sent as one prompt. Typing into a TUI
 * keystroke-by-keystroke is reserved for the terminal itself. Images can be attached from the
 * photo picker or inserted from the keyboard; see [ImageAttachments].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComposerSheet(
    title: String,
    agentStatus: AgentStatus?,
    isAgent: Boolean,
    onDismiss: () -> Unit,
    onQuickReply: (QuickReply) -> Unit,
    onSend: suspend (text: String, images: List<File>, uploads: AttachmentUploads) -> Result<Unit>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val uploads = remember { AttachmentUploads() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !sending })
    val text = rememberTextFieldState()
    val images = remember { mutableStateListOf<File>() }
    var preparing by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ImageAttachments.pruneStale(context) }
    }

    fun attach(uri: Uri): Boolean {
        if (sending || images.size + preparing >= MAX_IMAGES) return false
        preparing++
        scope.launch {
            try { images += ImageAttachments.prepare(context, uri) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, "Couldn't attach image: ${e.message}", Toast.LENGTH_LONG).show() }
            finally { preparing-- }
        }
        return true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        uris.take(MAX_IMAGES - images.size).forEach { attach(it) }
    }
    // Images committed by the keyboard (Gboard clipboard, screenshots, stickers) or pasted.
    val receiver = remember {
        ReceiveContentListener { content ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            content.consume { item -> item.uri?.let { attach(it) } ?: false }
        }
    }
    val canSend = (text.text.isNotBlank() || images.isNotEmpty()) && preparing == 0 && !sending

    ModalBottomSheet(onDismissRequest = { if (!sending) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (agentStatus != null) StatusPill(agentStatus, agentStatus.name.lowercase())
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (isAgent) AgentQuickReplies else ShellQuickReplies) { reply ->
                    AssistChip(enabled = !sending, onClick = { onQuickReply(reply) }, label = { Text(reply.label) })
                }
            }
            Text(
                "Quick keys send immediately to the pane.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (images.isNotEmpty() || preparing > 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.path }) { file ->
                        AttachmentThumbnail(file, enabled = !sending, onRemove = { images -= file; file.delete() })
                    }
                    if (preparing > 0) item {
                        Box(Modifier.size(THUMB_SIZE), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerField(
                    state = text,
                    focus = focus,
                    placeholder = if (isAgent) "Ask or instruct the agent…" else "Command to run…",
                    onAttach = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    attachEnabled = !sending && images.size + preparing < MAX_IMAGES,
                    enabled = !sending,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 220.dp)
                        .focusRequester(focus)
                        .contentReceiver(receiver),
                )
                FilledIconButton(
                    enabled = canSend,
                    onClick = send@ {
                        if (sending) return@send
                        sending = true
                        sendError = null
                        val message = text.text.toString()
                        val attachments = images.toList()
                        scope.launch {
                            try {
                                onSend(message, attachments, uploads).getOrThrow()
                                withContext(Dispatchers.IO) { attachments.forEach { it.delete() } }
                                text.clearText()
                                images.clear()
                                sending = false
                                onDismiss()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { sendError = e.message ?: "Couldn't send the message" }
                            finally { sending = false }
                        }
                    },
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.Send, "Send")
                }
            }
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                when {
                    images.isNotEmpty() -> "Images are sent to this machine with your message."
                    isAgent -> "Send a message to this agent."
                    else -> "Runs in this pane when you send."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/** An outlined multi-line field on [TextFieldState], which keyboard image insertion requires. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerField(
    state: TextFieldState,
    focus: FocusRequester,
    placeholder: String,
    onAttach: () -> Unit,
    attachEnabled: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(20.dp)
    BasicTextField(
        state = state,
        enabled = enabled,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        decorator = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = state.text.toString(),
                innerTextField = inner,
                enabled = enabled,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    IconButton(onClick = onAttach, enabled = attachEnabled) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "Attach image")
                    }
                },
                colors = colors,
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(enabled, false, interaction, colors, shape)
                },
            )
        },
    )
    // The bottom sheet composes its content separately. Request focus only
    // after this field's own composition has attached its focus target.
    LaunchedEffect(enabled) { if (enabled) focus.requestFocus() }
}

@Composable
private fun AttachmentThumbnail(file: File, enabled: Boolean, onRemove: () -> Unit) {
    val px = with(LocalDensity.current) { THUMB_SIZE.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { ImageAttachments.thumbnail(file, px)?.asImageBitmap() }
    }
    Box(Modifier.size(THUMB_SIZE)) {
        bitmap?.let {
            Image(
                it, contentDescription = "Attached image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
            )
        } ?: Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(enabled = enabled, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, "Remove image", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

private const val MAX_IMAGES = 5
private val THUMB_SIZE = 64.dp
