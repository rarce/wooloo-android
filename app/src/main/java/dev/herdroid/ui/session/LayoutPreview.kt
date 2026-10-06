package dev.herdroid.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.PaneLayoutSnapshot
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.ui.components.StatusDot
import dev.herdroid.ui.theme.LocalStatusColors

/**
 * A miniature of the tab exactly as herdr lays it out (from `pane.layout` rects), so the
 * phone shows the same spatial picture as the desktop. Tap a pane to open it.
 */
@Composable
fun LayoutPreview(
    layout: PaneLayoutSnapshot,
    snapshot: SessionSnapshot,
    onOpenPane: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val area = layout.area
    if (area.width <= 0 || area.height <= 0 || layout.panes.isEmpty()) return
    // Terminal cells are ~2x taller than wide; keep the preview's aspect honest but bounded.
    val aspect = (area.width.toFloat() / (area.height * 2f)).coerceIn(1.2f, 3.2f)
    val colors = LocalStatusColors.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val totalHeight = totalWidth / aspect
        Box(Modifier.size(totalWidth, totalHeight)) {
            layout.panes.forEach { pane ->
                val info = snapshot.pane(pane.paneId)
                val agent = snapshot.agentFor(pane.paneId)
                val status = agent?.agentStatus
                val x = totalWidth * ((pane.rect.x - area.x).toFloat() / area.width)
                val y = totalHeight * ((pane.rect.y - area.y).toFloat() / area.height)
                val w = totalWidth * (pane.rect.width.toFloat() / area.width)
                val h = totalHeight * (pane.rect.height.toFloat() / area.height)
                val accent = status?.let { colors.of(it) } ?: MaterialTheme.colorScheme.outline
                Box(
                    Modifier
                        .offset(x, y)
                        .size(w, h)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (status == AgentStatus.BLOCKED) accent.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .border(
                            width = if (pane.focused) 1.5.dp else 1.dp,
                            color = if (pane.focused) MaterialTheme.colorScheme.primary else accent.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onOpenPane(pane.paneId) }
                        .padding(6.dp),
                ) {
                    Text(
                        agent?.displayName ?: info?.displayTitle ?: pane.paneId,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                    if (status != null) {
                        StatusDot(status, size = 6.dp, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
    }
}
