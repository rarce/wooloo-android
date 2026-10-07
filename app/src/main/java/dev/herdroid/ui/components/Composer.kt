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
    onSend: (text: String, images: List<File>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val text = rememberTextFieldState()
    val images = remember { mutableStateListOf<File>() }
    var preparing by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        withContext(Dispatchers.IO) { ImageAttachments.pruneStale(context) }
    }

    fun attach(uri: Uri) {
        preparing++
        scope.launch {
            runCatching { ImageAttachments.prepare(context, uri) }
                .onSuccess { images += it }
                .onFailure { Toast.makeText(context, "Couldn't attach image: ${it.message}", Toast.LENGTH_LONG).show() }
            preparing--
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        uris.take(MAX_IMAGES - images.size).forEach(::attach)
    }
    // Images committed by the keyboard (Gboard clipboard, screenshots, stickers) or pasted.
    val receiver = remember {
        ReceiveContentListener { content ->
            if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
            content.consume { item -> item.uri?.let { attach(it); true } ?: false }
        }
    }
    val canSend = (text.text.isNotBlank() || images.isNotEmpty()) && preparing == 0

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (agentStatus != null) StatusPill(agentStatus, agentStatus.name.lowercase())
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (isAgent) AgentQuickReplies else ShellQuickReplies) { reply ->
                    AssistChip(onClick = { onQuickReply(reply) }, label = { Text(reply.label) })
                }
            }
            if (images.isNotEmpty() || preparing > 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.path }) { file ->
                        AttachmentThumbnail(file, onRemove = { images -= file; file.delete() })
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
                    placeholder = if (isAgent) "Ask or instruct the agent…" else "Command to run…",
                    onAttach = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    attachEnabled = images.size + preparing < MAX_IMAGES,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 220.dp)
                        .focusRequester(focus)
                        .contentReceiver(receiver),
                )
                FilledIconButton(
                    enabled = canSend,
                    onClick = {
                        onSend(text.text.toString(), images.toList())
                        text.clearText()
                        images.clear()
                        onDismiss()
                    },
                ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
            Text(
                when {
                    images.isNotEmpty() -> "Images are uploaded to a private temp folder on the host and their paths added to the message."
                    isAgent -> "Sent with herdr's agent prompt, like `herdr agent prompt`."
                    else -> "Typed into the pane followed by Enter."
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
    placeholder: String,
    onAttach: () -> Unit,
    attachEnabled: Boolean,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    val shape = RoundedCornerShape(20.dp)
    BasicTextField(
        state = state,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        decorator = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = state.text.toString(),
                innerTextField = inner,
                enabled = true,
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
                    OutlinedTextFieldDefaults.ContainerBox(true, false, interaction, colors, shape)
                },
            )
        },
    )
}

@Composable
private fun AttachmentThumbnail(file: File, onRemove: () -> Unit) {
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
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, "Remove image", tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}

private const val MAX_IMAGES = 5
private val THUMB_SIZE = 64.dp
