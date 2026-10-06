package dev.herdroid.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.Mono

/** One pane's place in a miniature, as fractions of the tab's area. */
private data class Tile(val paneId: String, val x: Float, val y: Float, val w: Float, val h: Float)

/** Pane geometry from herdr's layout; without one, the tab's panes side by side. */
private fun tiles(snapshot: SessionSnapshot, tabId: String): List<Tile> {
    val layout = snapshot.layout(tabId)
    val area = layout?.area
    if (layout != null && area != null && area.width > 0 && area.height > 0 && layout.panes.isNotEmpty()) {
        return layout.panes.map { pane ->
            Tile(
                pane.paneId,
                (pane.rect.x - area.x).toFloat() / area.width,
                (pane.rect.y - area.y).toFloat() / area.height,
                pane.rect.width.toFloat() / area.width,
                pane.rect.height.toFloat() / area.height,
            )
        }
    }
    val panes = snapshot.panesOf(tabId)
    return panes.mapIndexed { i, pane -> Tile(pane.paneId, i.toFloat() / panes.size, 0f, 1f / panes.size, 1f) }
}

/**
 * A miniature of a tab exactly as herdr lays it out, colored by agent state: the app's signature.
 * Blocked and done panes are solid, working ones a neutral tint, the rest an outline. The size
 * comes from [modifier]; [highlightPaneId] gets a strong outline.
 */
@Composable
fun TabMiniature(
    snapshot: SessionSnapshot,
    tabId: String,
    modifier: Modifier = Modifier,
    highlightPaneId: String? = null,
    gap: Dp = 2.dp,
    corner: Dp = 4.dp,
    labels: Boolean = false,
    onPaneClick: ((String) -> Unit)? = null,
) {
    val status = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    val tiles = tiles(snapshot, tabId)
    BoxWithConstraints(modifier) {
        val shape = RoundedCornerShape(corner)
        if (tiles.isEmpty()) {
            Box(Modifier.size(maxWidth, maxHeight).border(1.dp, scheme.outlineVariant, shape))
        }
        tiles.forEach { tile ->
            val pane = snapshot.pane(tile.paneId)
            val agent = snapshot.agentFor(tile.paneId)
            val state = agent?.agentStatus
            val solid = state != null && status.isSolid(state)
            val name = agent?.displayName ?: pane?.displayTitle ?: tile.paneId
            Box(
                Modifier
                    .offset(maxWidth * tile.x, maxHeight * tile.y)
                    .size(maxWidth * tile.w, maxHeight * tile.h)
                    .padding(gap / 2)
                    .clip(shape)
                    .then(
                        when {
                            solid -> Modifier.background(status.of(state!!))
                            state == AgentStatus.WORKING -> Modifier.background(status.working.copy(alpha = 0.16f))
                            else -> Modifier.border(1.dp, scheme.outlineVariant, shape)
                        }
                    )
                    .then(
                        if (tile.paneId == highlightPaneId) Modifier.border(2.dp, scheme.onSurface, shape) else Modifier
                    )
                    .then(
                        if (onPaneClick != null) {
                            Modifier
                                .clickable { onPaneClick(tile.paneId) }
                                .semantics { contentDescription = name + (state?.let { ", ${it.name.lowercase()}" } ?: "") }
                        } else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (labels) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (solid) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = if (agent == null) Mono else null,
                        color = when {
                            solid -> status.onSolid
                            agent != null -> scheme.onSurface
                            else -> scheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The labelled, full-width miniature of a tab. Tap a pane to open it. */
@Composable
fun LayoutPreview(
    snapshot: SessionSnapshot,
    tabId: String,
    onOpenPane: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val area = snapshot.layout(tabId)?.area
    // Terminal cells are ~2x taller than wide; keep the preview's aspect honest but bounded.
    val aspect = area?.takeIf { it.height > 0 }?.let { it.width.toFloat() / (it.height * 2f) }?.coerceIn(1.6f, 3.2f) ?: 2.4f
    TabMiniature(
        snapshot = snapshot,
        tabId = tabId,
        modifier = modifier.fillMaxWidth().aspectRatio(aspect),
        highlightPaneId = snapshot.layout(tabId)?.focusedPaneId,
        gap = 4.dp,
        corner = 10.dp,
        labels = true,
        onPaneClick = onOpenPane,
    )
}

/** Small miniatures: a workspace's active tab, or an agent's tab with its pane outlined. */
val MiniatureRow = Modifier.size(56.dp, 36.dp)
