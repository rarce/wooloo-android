package dev.herdroid.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.ui.theme.LocalStatusColors

/** A status dot. Working agents breathe, blocked agents pulse a halo to draw the eye. */
@Composable
fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = LocalStatusColors.current.of(status)
    val transition = rememberInfiniteTransition(label = "status")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(
        modifier
            .size(size * 2)
            .semantics { contentDescription = "Agent ${status.name.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        if (status == AgentStatus.BLOCKED) {
            Box(
                Modifier
                    .size(size)
                    .scale(1f + pulse * 0.9f)
                    .alpha(1f - pulse)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Box(
            Modifier
                .size(size)
                .alpha(if (status == AgentStatus.WORKING) breathe else 1f)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Solid when the agent needs you (blocked, done), outlined while working, plain text otherwise. */
@Composable
fun StatusPill(status: AgentStatus, label: String, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    val shape = RoundedCornerShape(50)
    val solid = colors.isSolid(status)
    Row(
        modifier
            .clip(shape)
            .then(
                when {
                    solid -> Modifier.background(colors.of(status))
                    status == AgentStatus.WORKING -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    else -> Modifier
                }
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (solid) FontWeight.Bold else FontWeight.Medium,
            color = when {
                solid -> colors.onSolid
                status == AgentStatus.WORKING -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
