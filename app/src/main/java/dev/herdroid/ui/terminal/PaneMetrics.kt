package dev.herdroid.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlin.math.roundToInt

/** Host resources and remaining subscription quota for the provider running in this pane. */
@Composable
internal fun PaneMetrics(connection: HerdrConnection, agent: String?) {
    val approved by LocalContext.current.app.preferences.agentQuotaContexts.collectAsState()
    val status by connection.status.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val contextKey = connection.activeProfile?.let(::quotaConsentKey)
    val active = status == ConnectionStatus.Connected && lifecycle == Lifecycle.State.RESUMED
    val provider = when (agent?.lowercase()) {
        "claude", "claude-code", "claude_code" -> AgentProvider.CLAUDE
        "codex" -> AgentProvider.CODEX
        else -> null
    }
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
    fun percent(value: Double?) = value?.let { "${(it * 100).roundToInt()}%" } ?: "–"
    Column(Modifier.widthIn(max = 150.dp).padding(horizontal = 6.dp)) {
        Text(
            "CPU ${percent(if (active) stats?.cpuUsage else null)} · RAM ${percent(if (active) stats?.memoryFraction else null)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (provider != null) {
            val state = states[provider]
            val windows = if (active && enabled && state?.failure == null) state?.quota?.windows.orEmpty() else emptyList()
            Text(
                "${provider.title} " + if (!enabled) "quota off" else windows.takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ", postfix = " left") { "${it.label} ${percent(1.0 - it.usedFraction(now))}" } ?: "quota –",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
