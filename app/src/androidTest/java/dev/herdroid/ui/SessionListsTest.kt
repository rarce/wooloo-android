package dev.herdroid.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdroid.connection.ConnectionStatus
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.*
import dev.herdroid.ui.hosts.HostItem
import dev.herdroid.ui.components.ListRowPosition
import dev.herdroid.ui.components.EmptyState
import dev.herdroid.ui.session.SpaceContent
import dev.herdroid.ui.session.SpacesContent
import dev.herdroid.ui.theme.WoolooMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the production lists on an emulator without opening a user's SSH session. */
@OptIn(ExperimentalMaterial3Api::class)
class SessionListsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf("integration-tests", "auth-refactor", "reviewer", "research", "diagnostics")
    private val states = listOf(AgentStatus.WORKING, AgentStatus.BLOCKED, AgentStatus.DONE, AgentStatus.IDLE, AgentStatus.UNKNOWN)
    private val details = listOf("Write integration tests", "Waiting to run the database migration",
        "Reviewed PR #142: three comments", "Research retry behavior", "Inspect the SSH connection")
    private val agents = names.mapIndexed { i, name ->
        AgentInfo(paneId = "w1:p$i", workspaceId = "w1", tabId = "w1:t1", name = name,
            agentStatus = states[i], title = details[i], focused = i == 0)
    }
    private val fixture = SessionSnapshot(
        workspaces = listOf(
            WorkspaceInfo("w1", number = 9, label = "api", paneCount = 5, tabCount = 1, agentStatus = AgentStatus.IDLE),
            WorkspaceInfo("w2", number = 2, label = "web", paneCount = 1, tabCount = 1, agentStatus = AgentStatus.WORKING),
            WorkspaceInfo("w3", number = 5, label = "infra", paneCount = 1, tabCount = 1, agentStatus = AgentStatus.BLOCKED),
        ),
        tabs = listOf(
            TabInfo("w1:t1", "w1", label = "1", paneCount = 5),
            TabInfo("w2:t1", "w2", label = "1", paneCount = 1),
            TabInfo("w3:t1", "w3", label = "1", paneCount = 1),
        ),
        panes = agents.map { PaneInfo(it.paneId, workspaceId = "w1", tabId = "w1:t1", agentStatus = it.agentStatus) } + listOf(
            PaneInfo("w2:p0", workspaceId = "w2", tabId = "w2:t1", agentStatus = AgentStatus.WORKING),
            PaneInfo("w3:p0", workspaceId = "w3", tabId = "w3:t1", agentStatus = AgentStatus.BLOCKED),
        ),
        layouts = listOf(PaneLayoutSnapshot("w1", "w1:t1", area = PaneLayoutRect(0, 0, 120, 40),
            focusedPaneId = "w1:p0", panes = agents.mapIndexed { index, agent ->
                PaneLayoutPane(agent.paneId, rect = if (index == 0) PaneLayoutRect(0, 0, 60, 40)
                    else PaneLayoutRect(60, (index - 1) * 10, 60, 10))
            })),
        agents = agents,
    )

    @Test fun lightListsPreserveServerStatesOrderAndCompactAgentRows() = review(false, 1f)
    @Test fun darkListsKeepLabelsAndReplyActionsUsableAtLargeFont() = review(true, 2f)
    @Test fun darkListsRemainCompactAtDefaultFont() = review(true, 1f)
    @Test fun lightListsKeepLabelsAndReplyActionsUsableAtLargeFont() = review(false, 2f)

    @Test fun emptyMachineActionsRemainReachableWithLargeTextAndShortViewport() {
        val shortViewport = mutableStateOf(false)
        var scans = 0
        var manualAdds = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WoolooMobileTheme(darkTheme = false) {
                    Scaffold { padding ->
                        Box(Modifier.padding(padding).fillMaxWidth().then(
                            if (shortViewport.value) Modifier.height(320.dp) else Modifier.fillMaxSize()
                        )) {
                            EmptyState(Icons.Outlined.Dns, "Add your first machine",
                                "Scan the Remote Access QR in wooloo, or add a machine to see its spaces and agents.") {
                                Button(onClick = { scans++ }) { Text("Scan Wooloo QR") }
                                Button(onClick = { manualAdds++ }) { Text("Add a machine") }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Scan Wooloo QR").performScrollTo().performClick()
        compose.runOnIdle { shortViewport.value = true }
        compose.onNodeWithText("Add a machine").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, scans); assertEquals(1, manualAdds) }
    }

    private fun review(dark: Boolean, scale: Float) {
        val page = mutableStateOf(0)
        val snapshot = mutableStateOf(fixture)
        var reply: String? = null
        val suffix = (if (dark) "dark" else "light") + if (scale > 1f) "-font200" else ""
        compose.activityRule.scenario.onActivity {
            val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            it.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                WoolooMobileTheme(darkTheme = dark) {
                    Scaffold(topBar = {
                        TopAppBar(title = {
                            Text(when (page.value) { 0 -> "Machines"; 1 -> "devbox"; else -> "api" })
                        }, expandedHeight = maxOf(64.dp, 48.dp * scale))
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            when (page.value) {
                                0 -> Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    HostItem(HostProfile(label = "devbox", hostname = "devbox.local", username = "roberto"),
                                        ConnectionStatus.Connected, snapshot.value, true, {}, {}, {}, {}, ListRowPosition(first = true, last = false))
                                    HostItem(HostProfile(label = "build server", hostname = "build.local", username = "roberto"),
                                        ConnectionStatus.Idle, null, false, {}, {}, {}, {}, ListRowPosition(first = false, last = true))
                                }
                                1 -> SpacesContent(snapshot.value, false, {}, {})
                                else -> SpaceContent(snapshot.value, "w1", false, {}, {},
                                    onCompose = { reply = it.paneId }, onAction = {}, onSplit = { _, _ -> })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        states.forEach { compose.onNodeWithText("1 ${it.name.lowercase()}").assertIsDisplayed() }
        capture("machines-$suffix")

        compose.runOnIdle { page.value = 1 }
        val spacePositions = listOf("api", "web", "infra").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(spacePositions.sorted(), spacePositions)
        // api remains idle even though one of its agents is blocked.
        compose.onNode(hasText("api") and hasContentDescription("Status idle")).assertIsDisplayed()
        capture("spaces-$suffix")

        compose.runOnIdle { page.value = 2 }
        capture("agents-$suffix")
        compose.onNodeWithContentDescription("Message integration-tests").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("w1:p0", reply) }
        if (scale == 1f) {
            val bounds = names.map { agentRow(it).fetchSemanticsNode().boundsInRoot }
            assertEquals(bounds.map { it.top }.sorted(), bounds.map { it.top })
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            bounds.forEach { assertTrue("Agent row should fit in 80dp: $it", it.height / density <= 80f) }
            names.forEach { agentRow(it).assertIsDisplayed() }
            compose.runOnIdle {
                snapshot.value = fixture.copy(agents = agents.map { it.copy(agentStatus = AgentStatus.DONE) })
            }
            assertEquals(bounds.map { it.top }, names.map { agentRow(it).fetchSemanticsNode().boundsInRoot.top })
            compose.runOnIdle { snapshot.value = fixture }
        }
        if (scale > 1f) {
            agentRow("diagnostics").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Message diagnostics").performClick()
            compose.runOnIdle { assertEquals("w1:p4", reply) }
        }
    }

    private fun agentRow(name: String) = compose.onNode(
        hasText(name) and hasAnyDescendant(hasContentDescription("Message $name"))
    )

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Let the device compositor present the settled frame, without capturing a tap ripple.
        android.os.SystemClock.sleep(200)
        val directory = File(instrumentation.targetContext.filesDir, "list-review").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
