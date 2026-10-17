package dev.herdroid.ui.terminal

import dev.herdroid.connection.AgentQuotaMonitor
import dev.herdroid.herdr.AgentProvider
import kotlin.math.roundToInt

internal fun paneProvider(agent: String?): AgentProvider? = when (agent?.trim()?.lowercase()) {
    "claude", "claude-code", "claude_code" -> AgentProvider.CLAUDE
    "codex" -> AgentProvider.CODEX
    else -> null
}

internal fun metricPercent(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let { "${(it.coerceIn(0.0, 1.0) * 100).roundToInt()}%" } ?: "–"

/** Never substitutes another provider's quota, or presents missing/stale data as available capacity. */
internal fun paneQuotaText(
    provider: AgentProvider?,
    state: AgentQuotaMonitor.State?,
    enabled: Boolean,
    active: Boolean,
    now: Long,
): String? {
    provider ?: return null
    if (!enabled) return "${provider.title} quota off"
    val windows = if (active && state?.failure == null) state?.quota?.windows.orEmpty() else emptyList()
    if (windows.isEmpty()) return "${provider.title} quota –"
    return provider.title + " " + windows.joinToString(" · ", postfix = " left") {
        "${it.label} ${metricPercent(1.0 - it.usedFraction(now))}"
    }
}
