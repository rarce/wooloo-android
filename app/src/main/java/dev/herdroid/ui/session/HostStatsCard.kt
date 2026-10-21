package dev.herdroid.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.data.quotaConsentKey
import dev.herdroid.herdr.HostStats
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.MonoSmall
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val SAMPLE_INTERVAL_MS = 5_000L

/** The latest host stats and read error, sampled every few seconds while the screen is resumed and connected. */
private class HostStatsState {
    var stats by mutableStateOf<HostStats?>(null)
    var failure by mutableStateOf<String?>(null)
}

/**
 * The machine's CPU, memory, disk, GPUs and uptime, as in wooloo's HOST sidebar section; collapsed,
 * only CPU, memory and GPU. Sampling runs on its own exec channel and pauses when the screen isn't visible.
 */
@Composable
internal fun HostStatsCard(connection: HerdrConnection, modifier: Modifier = Modifier) {
    val status by connection.status.collectAsState()
    val contextKey = connection.activeProfile?.let(::quotaConsentKey)
    val state = remember(connection, contextKey) { HostStatsState() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = status == ConnectionStatus.Connected && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var collapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(connection, active, contextKey) {
        if (!active) return@LaunchedEffect
        while (true) {
            connection.sampleHost()
                .onSuccess { sample ->
                    if (connection.activeProfile?.let(::quotaConsentKey) == contextKey) {
                        state.stats = HostStats.from(sample, state.stats)
                        state.failure = null
                    }
                }
                .onFailure {
                    if (connection.activeProfile?.let(::quotaConsentKey) == contextKey) {
                        state.failure = it.message ?: "Couldn't read host stats"
                    }
                }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    val stats = state.stats
    StatsCard(
        title = stats?.sample?.hostname ?: "Host",
        dot = when {
            state.failure != null -> MaterialTheme.colorScheme.error
            stats == null -> MaterialTheme.colorScheme.outline
            else -> LocalStatusColors.current.idle
        },
        collapsed = collapsed,
        onToggle = { collapsed = !collapsed },
        what = "host details",
        modifier = modifier,
        trailing = {
            stats?.sample?.uptimeSeconds?.let {
                Text("up ${HostStats.uptimeText(it)}", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        when {
            stats == null -> Text(
                state.failure ?: if (status == ConnectionStatus.Connected) "Reading…" else "Waiting for the machine…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> HostMeters(stats, collapsed)
        }
    }
}

/** The card's meters. GPUs appear only when the host reports some; more than two share one meter. */
@Composable
internal fun HostMeters(stats: HostStats, collapsed: Boolean) {
    val sample = stats.sample
    if (collapsed) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniMeter("CPU", stats.cpuUsage, Modifier.weight(1f))
            MiniMeter("MEM", stats.memoryFraction, Modifier.weight(1f))
            if (sample.gpus.isNotEmpty()) MiniMeter("GPU", stats.gpuUsage, Modifier.weight(1f))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Meter(
            "CPU", stats.cpuUsage,
            (stats.cpuUsage?.let { percent(it) + "%" } ?: "…") +
                (sample.cpuCount?.let { " / $it cores" } ?: ""),
        )
        if (sample.memoryUsed != null && sample.memoryTotal != null) {
            Meter(
                "MEM", stats.memoryFraction,
                "${HostStats.bytesText(sample.memoryUsed)} / ${HostStats.bytesText(sample.memoryTotal)}",
            )
        }
        sample.diskFree?.let { Meter("DISK", stats.diskUsedFraction, "${HostStats.bytesText(it)} free") }
        val gpus = sample.gpus
        when {
            gpus.size == 1 -> Meter("GPU", gpus[0].utilization, HostStats.gpuText(gpus[0]))
            gpus.size == 2 -> gpus.forEachIndexed { i, gpu -> Meter("GPU${i + 1}", gpu.utilization, HostStats.gpuText(gpu)) }
            gpus.size > 2 -> Meter("GPU", stats.gpuUsage, HostStats.gpusText(gpus))
        }
    }
}

/** A rounded card with a tappable header (status dot, title, [trailing], chevron) that collapses its body. */
@Composable
internal fun StatsCard(
    title: String,
    dot: Color,
    collapsed: Boolean,
    onToggle: () -> Unit,
    what: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    hasBody: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (collapsed) "Show $what" else "Hide $what", onClick = onToggle)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = if (!hasBody) 10.dp else if (collapsed) 4.dp else 6.dp)
                .semantics { stateDescription = if (collapsed) "Collapsed" else "Expanded" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing()
            Icon(
                if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasBody) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { content() }
    }
}

internal fun percent(fraction: Double) = (fraction * 100).roundToInt().toString()

/** Sky blue, turning to herdr's working (amber) color at 75% and to error red at 90%. */
@Composable
private fun meterColor(fraction: Double?): Color {
    val value = fraction ?: 0.0
    return when {
        value >= 0.9 -> MaterialTheme.colorScheme.error
        value >= 0.75 -> LocalStatusColors.current.working
        else -> MaterialTheme.colorScheme.secondary
    }
}

@Composable
private fun Bar(fraction: Double?, modifier: Modifier) {
    Box(modifier.height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth((fraction ?: 0.0).toFloat().coerceIn(0f, 1f))
                .clip(CircleShape).background(meterColor(fraction)),
        )
    }
}

@Composable
internal fun Meter(label: String, fraction: Double?, value: String, labelWidth: Dp = 44.dp) {
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$label $value" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelWidth),
            )
            Text(
                value, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        Bar(fraction, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun MiniMeter(label: String, fraction: Double?, modifier: Modifier) {
    val text = fraction?.let(::percent) ?: "–"
    Row(
        modifier.clearAndSetSemantics { contentDescription = if (fraction == null) label else "$label $text%" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Bar(fraction, Modifier.weight(1f))
        Text(text, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
