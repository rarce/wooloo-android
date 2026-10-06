package dev.herdroid.ui.components

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
 * keystroke-by-keystroke is reserved for the terminal itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheet(
    title: String,
    agentStatus: AgentStatus?,
    isAgent: Boolean,
    onDismiss: () -> Unit,
    onQuickReply: (QuickReply) -> Unit,
    onSend: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
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
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (isAgent) "Ask or instruct the agent…" else "Command to run…") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp, max = 220.dp)
                        .focusRequester(focus),
                )
                FilledIconButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onSend(text)
                        text = ""
                        onDismiss()
                    },
                ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
            }
            Text(
                if (isAgent) "Sent with herdr's agent prompt, like `herdr agent prompt`." else "Typed into the pane followed by Enter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}
