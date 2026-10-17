package dev.herdroid.ui.terminal

import dev.herdroid.connection.AgentQuotaMonitor
import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.AgentQuota
import dev.herdroid.herdr.QuotaWindow
import org.junit.Assert.*
import org.junit.Test

class PaneMetricsModelTest {
    private val now = 1_000L
    private fun state(used: Double = 0.3, reset: Long? = 2_000) = AgentQuotaMonitor.State(
        AgentQuota(listOf(QuotaWindow("primary", "5H", used, reset)), null, now))

    @Test fun recognizesOnlySupportedProvidersAndNormalizesNames() {
        listOf("claude", "Claude-Code", " claude_code ").forEach {
            assertEquals(AgentProvider.CLAUDE, paneProvider(it))
        }
        assertEquals(AgentProvider.CODEX, paneProvider("CODEX"))
        listOf(null, "", "sh", "pi", "my codex project").forEach { assertNull(paneProvider(it)) }
    }

    @Test fun displaysRemainingInsteadOfUsedForEachWindow() {
        val state = state().copy(quota = state().quota!!.copy(windows = listOf(
            QuotaWindow("primary", "5H", 0.3, 2_000), QuotaWindow("weekly", "7D", 0.85, null))))
        assertEquals("Codex 5H 70% · 7D 15% left", paneQuotaText(AgentProvider.CODEX, state, true, true, now))
    }

    @Test fun resetWindowShowsFullRemainingCapacity() {
        assertEquals("Claude 5H 100% left", paneQuotaText(AgentProvider.CLAUDE, state(1.0, now), true, true, now))
    }

    @Test fun noConsentOrInactiveConnectionDoesNotExposeCachedQuota() {
        assertEquals("Codex quota off", paneQuotaText(AgentProvider.CODEX, state(), false, true, now))
        assertEquals("Codex quota –", paneQuotaText(AgentProvider.CODEX, state(), true, false, now))
        assertNull(paneQuotaText(null, state(), true, true, now))
    }

    @Test fun absentEmptyOrFailedReadingsNeverImplyFullCapacity() {
        val readings = listOf(null, AgentQuotaMonitor.State(), state().copy(failure = "offline"),
            state().copy(quota = state().quota!!.copy(windows = emptyList())))
        readings.forEach { assertEquals("Claude quota –", paneQuotaText(AgentProvider.CLAUDE, it, true, true, now)) }
    }

    @Test fun percentagesHandleBoundariesMissingAndInvalidNumbers() {
        assertEquals("0%", metricPercent(-0.2))
        assertEquals("100%", metricPercent(1.2))
        assertEquals("34%", metricPercent(0.335))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertEquals("–", metricPercent(it)) }
        assertEquals("Codex 5H 0% left", paneQuotaText(AgentProvider.CODEX, state(1.0), true, true, now))
        assertEquals("Claude 5H 100% left", paneQuotaText(AgentProvider.CLAUDE, state(0.0), true, true, now))
    }
}
