package dev.herdroid.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.herdroid.app
import dev.herdroid.data.quotaConsentKey
import dev.herdroid.connection.AgentQuotaMonitor
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.connection.HerdrConnection
import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.HostStats
import dev.herdroid.ui.theme.LocalStatusColors
import dev.herdroid.ui.theme.MonoSmall
import kotlinx.coroutines.delay

/**
 * How much of the Claude Code and Codex subscription limits have been used on this machine, and
 * when each window resets (wooloo's QUOTAS section). The agents run and are signed in on the
 * machine, so that's where their sign-ins are read. Collapsed, it shows each agent's most used
 * window. It only polls while visible, and only after the user turns it on.
 */
@Composable
internal fun AgentQuotaCard(connection: HerdrConnection, modifier: Modifier = Modifier) {
    val prefs = LocalContext.current.app.preferences
    val approvedContexts by prefs.agentQuotaContexts.collectAsState()
    val status by connection.status.collectAsState()
    val quotaProfile = connection.activeProfile ?: connection.profile
    val enabled = quotaConsentKey(quotaProfile) in approvedContexts
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = enabled && status == ConnectionStatus.Connected && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var collapsed by rememberSaveable { mutableStateOf(true) }

    val monitor = connection.quotas
    DisposableEffect(monitor, active, enabled, quotaConsentKey(quotaProfile)) {
        if (active) monitor.acquire()
        if (!enabled) monitor.clear()
        onDispose { if (active) monitor.release() }
    }
    val states by monitor.states.collectAsState()
    val loaded by monitor.loaded.collectAsState()
    // Once a minute, for the reset countdowns and the age of old readings.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val colors = LocalStatusColors.current

    StatsCard(
        title = "Agent quotas",
        dot = when {
            !enabled || states.isEmpty() -> MaterialTheme.colorScheme.outline
            states.values.any { it.quota == null } -> MaterialTheme.colorScheme.error
            states.values.any { it.failure != null } -> colors.working
            else -> colors.idle
        },
        collapsed = collapsed,
        onToggle = { collapsed = !collapsed },
        what = "agent quotas",
        modifier = modifier,
        hasBody = enabled || !collapsed,
        trailing = {
            if (!enabled) Text("Off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        when {
            !enabled -> OptIn(onEnable = { prefs.setAgentQuotas(quotaProfile, true) })
            states.isEmpty() -> Hint(
                if (loaded) "No Claude Code or Codex sign-in on ${connection.profile.displayName}" else "Reading…",
            )
            collapsed -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AgentProvider.entries.forEach { provider ->
                    states[provider]?.let { state ->
                        // The most used window is the one that blocks the agent first.
                        val window = state.quota?.windows?.maxByOrNull { it.usedFraction(now) }
                        MiniMeter(provider.title, window?.usedFraction(now), Modifier.weight(1f))
                    }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentProvider.entries.forEach { provider ->
                    states[provider]?.let { Provider(provider, it, now) }
                }
            }
        }
        if (enabled && !collapsed) {
            TextButton(onClick = { prefs.setAgentQuotas(quotaProfile, false); monitor.clear() }) { Text("Turn off") }
        }
    }
}

@Composable
private fun OptIn(onEnable: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "Shows how much of your Claude Code and Codex plans you have used. To read it, the app reads " +
                "their sign-ins on this machine (~/.claude, Claude Code's Keychain item and ~/.codex) over SSH " +
                "and sends the tokens to Anthropic and OpenAI every two minutes.",
            maxLines = Int.MAX_VALUE,
        )
        FilledTonalButton(onClick = onEnable) { Text("Turn on quotas") }
    }
}

@Composable
private fun Hint(text: String, maxLines: Int = 2) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Provider(provider: AgentProvider, state: AgentQuotaMonitor.State, now: Long) {
    val colors = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dot = when {
                state.failure == null -> colors.idle
                state.quota == null -> MaterialTheme.colorScheme.error
                else -> colors.working
            }
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Text(provider.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            state.quota?.plan?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
            Box(Modifier.weight(1f))
            val quota = state.quota
            if (quota != null && (state.failure != null || quota.fromLog) && now - quota.capturedAt >= 60_000) {
                Text("${HostStats.uptimeText((now - quota.capturedAt) / 1000.0)} ago", style = MonoSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        val quota = state.quota
        if (quota != null) {
            quota.windows.forEach { window ->
                val used = window.usedFraction(now)
                Meter(window.label, used, quotaValue(used, window.resetsAt, now), labelWidth = 72.dp)
            }
            state.failure?.let { Hint(it) }
        } else {
            state.failure?.let { Hint(it) }
        }
        if (quota?.fromLog == true && state.failure == null) {
            Hint("Read from Codex's latest session log", maxLines = 1)
        }
    }
}

/** `40% · 2h 13m`: the share used and the time left until the window resets. */
internal fun quotaValue(used: Double, resetsAt: Long?, now: Long): String {
    val percent = percent(used) + "%"
    if (resetsAt == null || resetsAt <= now) return percent
    return "$percent · ${HostStats.uptimeText((resetsAt - now) / 1000.0)}"
}
