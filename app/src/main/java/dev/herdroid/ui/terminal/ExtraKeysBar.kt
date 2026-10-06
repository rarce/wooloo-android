package dev.herdroid.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdroid.terminal.ModifierState
import dev.herdroid.terminal.SpecialKey
import dev.herdroid.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The keys a phone keyboard lacks. Ctrl/Alt are sticky (tap = next key, double-tap = lock),
 * arrows auto-repeat while held, and the composer button opens the prompt sheet.
 */
@Composable
fun ExtraKeysBar(
    modifiers: ModifierState,
    haptics: Boolean,
    onSpecial: (SpecialKey) -> Unit,
    onText: (String) -> Unit,
    onPaste: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onComposer: () -> Unit,
    composerHighlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    var ctrl by remember { mutableStateOf(modifiers.ctrl) }
    var alt by remember { mutableStateOf(modifiers.alt) }
    var ctrlLocked by remember { mutableStateOf(false) }
    var altLocked by remember { mutableStateOf(false) }
    LaunchedEffect(modifiers) {
        modifiers.onChange = {
            ctrl = modifiers.ctrl; alt = modifiers.alt
            ctrlLocked = modifiers.ctrlLocked; altLocked = modifiers.altLocked
        }
    }
    val haptic = LocalHapticFeedback.current
    fun buzz() { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton("esc", onTap = { buzz(); onSpecial(SpecialKey.ESCAPE) })
            KeyButton("tab", onTap = { buzz(); onSpecial(SpecialKey.TAB) })
            ModifierKey("ctrl", ctrl, ctrlLocked,
                onTap = {
                    buzz()
                    modifiers.ctrl = !modifiers.ctrl
                    modifiers.ctrlLocked = false
                    modifiers.onChange?.invoke()
                },
                onDoubleTap = {
                    buzz()
                    modifiers.ctrl = true
                    modifiers.ctrlLocked = true
                    modifiers.onChange?.invoke()
                },
            )
            ModifierKey("alt", alt, altLocked,
                onTap = {
                    buzz()
                    modifiers.alt = !modifiers.alt
                    modifiers.altLocked = false
                    modifiers.onChange?.invoke()
                },
                onDoubleTap = {
                    buzz()
                    modifiers.alt = true
                    modifiers.altLocked = true
                    modifiers.onChange?.invoke()
                },
            )
            KeyButton("←", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.LEFT) })
            KeyButton("↓", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.DOWN) })
            KeyButton("↑", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.UP) })
            KeyButton("→", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.RIGHT) })
            KeyButton("^C", onTap = { buzz(); onText("\u0003") })
            KeyButton("⏎", onTap = { buzz(); onSpecial(SpecialKey.ENTER) })
            listOf("/", "|", "-", "~", "_", ":").forEach { symbol ->
                KeyButton(symbol, onTap = { buzz(); onText(symbol) })
            }
            KeyButton("home", onTap = { buzz(); onSpecial(SpecialKey.HOME) })
            KeyButton("end", onTap = { buzz(); onSpecial(SpecialKey.END) })
            KeyButton("pgup", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.PAGE_UP) })
            KeyButton("pgdn", repeat = true, onTap = { buzz(); onSpecial(SpecialKey.PAGE_DOWN) })
            IconKey(Icons.Outlined.ContentPaste, "Paste", onTap = { buzz(); onPaste() })
        }
        IconKey(Icons.Outlined.Keyboard, "Toggle keyboard", onTap = onToggleKeyboard)
        IconKey(
            Icons.AutoMirrored.Outlined.Chat,
            "Compose prompt",
            highlighted = composerHighlighted,
            onTap = { buzz(); onComposer() },
        )
    }
}

@Composable
private fun KeyButton(label: String, repeat: Boolean = false, onTap: () -> Unit) {
    val currentOnTap by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    if (repeat && pressed) {
        LaunchedEffect(Unit) {
            delay(380)
            while (isActive) {
                currentOnTap()
                delay(55)
            }
        }
    }
    Box(
        Modifier
            .height(38.dp)
            .widthIn(min = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (pressed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .pointerInput(repeat) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnTap()
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Mono, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ModifierKey(label: String, active: Boolean, locked: Boolean, onTap: () -> Unit, onDoubleTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(38.dp)
            .widthIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    locked -> colors.primary
                    active -> colors.primaryContainer
                    else -> colors.surfaceContainerHighest
                }
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Mono,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = when {
                locked -> colors.onPrimary
                active -> colors.onPrimaryContainer
                else -> colors.onSurface
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, highlighted: Boolean = false, onTap: () -> Unit) {
    Box(
        Modifier
            .padding(start = 4.dp)
            .height(38.dp)
            .widthIn(min = 42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
