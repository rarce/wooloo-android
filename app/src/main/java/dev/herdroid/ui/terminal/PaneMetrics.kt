package dev.herdroid.ui.terminal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.herdroid.app
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.data.quotaConsentKey
import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.HostStats
import kotlinx.coroutines.delay

/** Host resources and remaining subscription quota for the provider running in this pane. */
@Composable
internal fun PaneMetrics(connection: HerdrConnection, agent: String?) {
    val approved by LocalContext.current.app.preferences.agentQuotaContexts.collectAsState()
    val status by connection.status.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val contextKey = connection.activeProfile?.let(::quotaConsentKey)
    val active = status == ConnectionStatus.Connected && lifecycle == Lifecycle.State.RESUMED
    // Nothing is signed in on the demo machine.
    val provider = if (connection.profile.demo) null else paneProvider(agent)
    var stats by remember(connection, contextKey) { mutableStateOf<HostStats?>(null) }
    LaunchedEffect(connection, contextKey, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            connection.sampleHost()
                .onSuccess { if (connection.activeProfile?.let(::quotaConsentKey) == contextKey) stats = HostStats.from(it, stats) }
                .onFailure { stats = null }
            delay(5_000)
        }
    }
    val monitor = connection.quotas
    val enabled = contextKey != null && contextKey in approved
    val polling = active && enabled && provider != null
    DisposableEffect(monitor, polling, contextKey) {
        if (polling) monitor.acquire()
        onDispose { if (polling) monitor.release() }
    }
    val states by monitor.states.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    PaneMetricsContent(
        if (active) stats else null,
        paneQuotaText(provider, states[provider], enabled, active, now),
    )
}

@Composable
internal fun PaneMetricsContent(stats: HostStats?, quotaText: String?) {
    val expanded = LocalDensity.current.fontScale > 1.3f || LocalConfiguration.current.screenWidthDp < 360
    Column((if (expanded) Modifier.fillMaxWidth() else Modifier.widthIn(max = 150.dp)).padding(horizontal = 6.dp)) {
        Text(
            "CPU ${metricPercent(stats?.cpuUsage)} · RAM ${metricPercent(stats?.memoryFraction)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (quotaText != null) Text(
            quotaText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
