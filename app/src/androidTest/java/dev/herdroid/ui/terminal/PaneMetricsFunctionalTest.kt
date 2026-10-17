package dev.herdroid.ui.terminal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.herdroid.connection.AgentQuotaMonitor
import dev.herdroid.herdr.*
import dev.herdroid.ui.components.StatusPill
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Rule
import org.junit.Test

class PaneMetricsFunctionalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingAgentShowsOnlyItsRemainingQuotaBesideStatus() {
        val provider = mutableStateOf<AgentProvider?>(AgentProvider.CODEX)
        val states = mapOf(
            AgentProvider.CODEX to AgentQuotaMonitor.State(AgentQuota(listOf(QuotaWindow("codex", "5H", 0.3, null)), null, 1)),
            AgentProvider.CLAUDE to AgentQuotaMonitor.State(AgentQuota(listOf(QuotaWindow("claude", "5H", 0.8, null)), null, 1)))
        compose.setContent { WoolooMobileTheme { Row {
            PaneMetricsContent(HostStats(HostSample("host", memoryUsed = 50, memoryTotal = 100), 0.25),
                paneQuotaText(provider.value, states[provider.value], true, true, 1))
            StatusPill(AgentStatus.WORKING, "working")
        } } }
        compose.onNodeWithText("CPU 25% · RAM 50%").assertIsDisplayed()
        compose.onNodeWithText("working").assertIsDisplayed()
        compose.onNodeWithText("Codex 5H 70% left").assertIsDisplayed()
        compose.onAllNodes(hasText("Claude", substring = true)).assertCountEquals(0)
        compose.runOnIdle { provider.value = AgentProvider.CLAUDE }
        compose.onNodeWithText("Claude 5H 20% left").assertIsDisplayed()
        compose.onAllNodes(hasText("Codex", substring = true)).assertCountEquals(0)
        compose.runOnIdle { provider.value = null }
        compose.onAllNodes(hasText("left", substring = true)).assertCountEquals(0)
    }
}
