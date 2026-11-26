package dev.herdroid.ui.terminal

import dev.herdroid.connection.AgentAlert
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertBannerTest {
    private fun alert(pane: String, space: String, status: AgentStatus, label: String? = "ridium") = AgentAlert(
        hostId = "h", hostName = "host",
        agent = AgentInfo(paneId = pane, workspaceId = space, tabId = "t", agentStatus = status, name = "agent-$pane"),
        workspaceLabel = label,
    )

    @Test fun ignoresTheOpenPane() {
        assertNull(alertBanner(alert("p1", "w1", AgentStatus.BLOCKED), currentPaneId = "p1", currentWorkspaceId = "w1"))
    }

    @Test fun showsFinishedOnlyFromTheSameSpace() {
        val here = alertBanner(alert("p2", "w1", AgentStatus.DONE), "p1", "w1")!!
        assertEquals(false, here.elsewhere)
        assertEquals("p2", here.paneId)
        assertEquals(true, here.message.endsWith(" finished"))
        assertEquals(false, here.message.contains(" in ridium"))
        assertNull(alertBanner(alert("p3", "w2", AgentStatus.DONE), "p1", "w1"))
    }

    @Test fun showsBlockedFromAnySpace() {
        val elsewhere = alertBanner(alert("p3", "w2", AgentStatus.BLOCKED), "p1", "w1")!!
        assertEquals(true, elsewhere.elsewhere)
        assertEquals("w2", elsewhere.workspaceId)
        assertEquals(true, elsewhere.message.endsWith(" in ridium needs input"))
        assertEquals(false, alertBanner(alert("p2", "w1", AgentStatus.BLOCKED), "p1", "w1")!!.elsewhere)
    }

    @Test fun aNewAlertReplacesTheVisibleOneAndIgnoredOnesDont() = runTest(UnconfinedTestDispatcher()) {
        val alerts = MutableSharedFlow<AgentAlert>()
        val shown = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        val job = launch {
            presentAlerts(alerts, banner = { alertBanner(it, "p1", "w1") }) { banner ->
                shown += banner.paneId
                try { awaitCancellation() } catch (e: CancellationException) { replaced += banner.paneId; throw e }
            }
        }
        alerts.emit(alert("p2", "w1", AgentStatus.BLOCKED))
        alerts.emit(alert("p3", "w2", AgentStatus.DONE)) // filtered: leaves p2 up
        assertEquals(listOf("p2"), shown)
        assertEquals(emptyList<String>(), replaced)
        alerts.emit(alert("p4", "w2", AgentStatus.BLOCKED))
        assertEquals(listOf("p2", "p4"), shown)
        assertEquals(listOf("p2"), replaced)
        job.cancel()
    }

    @Test fun aFinishedAgentDoesNotHideOneThatNeedsInput() = runTest(UnconfinedTestDispatcher()) {
        val alerts = MutableSharedFlow<AgentAlert>()
        val shown = mutableListOf<String>()
        var dismiss = CompletableDeferred<Unit>()
        val job = launch {
            presentAlerts(alerts, banner = { alertBanner(it, "p1", "w1") }) { banner ->
                shown += banner.paneId
                dismiss.await()
            }
        }
        alerts.emit(alert("p2", "w1", AgentStatus.DONE))
        alerts.emit(alert("p3", "w1", AgentStatus.BLOCKED)) // needs input replaces finished
        alerts.emit(alert("p4", "w1", AgentStatus.DONE))    // dropped while p3 is up
        assertEquals(listOf("p2", "p3"), shown)
        // Once the blocked banner is gone, a later finished one shows; the dropped one never does.
        dismiss.complete(Unit)
        dismiss = CompletableDeferred()
        alerts.emit(alert("p5", "w1", AgentStatus.DONE))
        assertEquals(listOf("p2", "p3", "p5"), shown)
        job.cancel()
    }
}
