package dev.herdroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.LocalStatusIndicators
import dev.herdroid.ui.theme.StatusIndicators
import kotlin.math.max

/**
 * herdr's state mark (`status_icon` in herdr's client), drawn rather than typeset so it stays
 * crisp at any size. Static, like herdr's: dots are ● for working/blocked/done, ○ idle, · unknown;
 * symbols are × blocked, ◐ working, ✓ done, ○ idle, · unknown.
 */
@Composable
fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = LocalStatusColors.current.of(status)
    val style = LocalStatusIndicators.current
    Canvas(
        modifier
            .size(size * 2)
            .semantics { contentDescription = "Status ${status.name.lowercase()}" },
    ) { drawStatusMark(status, style, color, size.toPx()) }
}

/** Draws the mark for [status] centered in this scope, [diameter] across. */
fun DrawScope.drawStatusMark(status: AgentStatus, style: StatusIndicators, color: Color, diameter: Float) {
    val r = diameter / 2
    val stroke = max(diameter * 0.18f, 1.5f)
    val ring = { drawCircle(color, r - stroke / 2, style = Stroke(stroke)) }
    when {
        status == AgentStatus.UNKNOWN -> drawCircle(color, r * 0.38f)
        status == AgentStatus.IDLE -> ring()
        style == StatusIndicators.DOTS -> drawCircle(color, r)
        status == AgentStatus.WORKING -> {
            ring()
            drawArc(
                color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                topLeft = Offset(center.x - r, center.y - r), size = Size(diameter, diameter),
            )
        }
        status == AgentStatus.BLOCKED -> {
            val d = r * 0.72f
            val line = stroke * 1.2f
            drawLine(color, center + Offset(-d, -d), center + Offset(d, d), line, StrokeCap.Round)
            drawLine(color, center + Offset(-d, d), center + Offset(d, -d), line, StrokeCap.Round)
        }
        else -> { // DONE
            val origin = center - Offset(r, r)
            val check = Path().apply {
                moveTo(origin.x + diameter * 0.12f, origin.y + diameter * 0.55f)
                lineTo(origin.x + diameter * 0.40f, origin.y + diameter * 0.82f)
                lineTo(origin.x + diameter * 0.90f, origin.y + diameter * 0.22f)
            }
            drawPath(check, color, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
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
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val markColor = if (solid) colors.onSolid else colors.of(status)
        val style = LocalStatusIndicators.current
        Canvas(Modifier.size(8.dp)) { drawStatusMark(status, style, markColor, size.minDimension) }
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

/** The same mark and label for spaces, tabs and agents, without an attention-only fill. */
@Composable
fun StatusLabel(status: AgentStatus, label: String = status.name.lowercase(), modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusDot(status, size = 6.dp)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Agent facts, distinct from a machine's SSH connection state. Includes idle/unknown agents. */
@Composable
fun AgentStatusSummary(agents: List<AgentInfo>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (agents.isEmpty()) {
            Text("No agents", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            listOf(AgentStatus.WORKING, AgentStatus.BLOCKED, AgentStatus.DONE, AgentStatus.IDLE, AgentStatus.UNKNOWN).forEach { state ->
                val count = agents.count { it.agentStatus == state }
                if (count > 0) StatusLabel(state, "$count ${state.name.lowercase()}")
            }
        }
    }
}
