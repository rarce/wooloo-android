package dev.herdroid.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Rounded corners belong to the group; separators preserve each row's tap target. */
internal data class ListRowPosition(val first: Boolean = true, val last: Boolean = true) {
    companion object {
        fun at(index: Int, size: Int) = ListRowPosition(index == 0, index == size - 1)
    }
}

/** Focus is a neutral rail. Herdr's state mark remains independent of selection. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionListRow(
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    actionLabel: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    position: ListRowPosition = ListRowPosition(),
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(
        topStart = if (position.first) 14.dp else 0.dp,
        topEnd = if (position.first) 14.dp else 0.dp,
        bottomStart = if (position.last) 14.dp else 0.dp,
        bottomEnd = if (position.last) 14.dp else 0.dp,
    )
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (focused) colors.surfaceContainerHigh else colors.surfaceContainer)
            .drawBehind {
                if (focused) drawRect(colors.onSurfaceVariant,
                    topLeft = Offset(0f, 10.dp.toPx()), size = Size(2.dp.toPx(), size.height - 20.dp.toPx()))
            }
            .semantics { if (focused) stateDescription = "Focused in herdr" }
            .combinedClickable(onClick = onOpen, onLongClickLabel = actionLabel, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading?.let { Box(contentAlignment = Alignment.Center) { it() } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
            trailing()
        }
        if (!position.last) {
            HorizontalDivider(Modifier.padding(start = 14.dp), color = colors.outlineVariant.copy(alpha = 0.65f))
        }
    }
}
