package dev.herdroid.herdr

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    private val response = """
        {"id":"x","result":{"type":"session_snapshot","snapshot":{
          "version":"0.7.2","protocol":22,"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",
          "workspaces":[{"workspace_id":"w1","number":1,"label":"api","focused":true,"pane_count":2,"tab_count":1,"active_tab_id":"w1:t1","agent_status":"blocked","future_field":1}],
          "tabs":[{"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"main","focused":true,"pane_count":2,"agent_status":"blocked"}],
          "panes":[
            {"pane_id":"w1:p2","terminal_id":"t2","workspace_id":"w1","tab_id":"w1:t1","focused":false,"agent_status":"unknown","revision":1,"cwd":"/srv/api"},
            {"pane_id":"w1:p1","terminal_id":"t1","workspace_id":"w1","tab_id":"w1:t1","focused":true,"agent_status":"blocked","agent":"claude","revision":9,"terminal_title_stripped":"Refactor auth"}
          ],
          "layouts":[{"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"area":{"x":0,"y":0,"width":200,"height":50},"focused_pane_id":"w1:p1",
            "panes":[{"pane_id":"w1:p1","focused":true,"rect":{"x":0,"y":0,"width":100,"height":50}},{"pane_id":"w1:p2","focused":false,"rect":{"x":100,"y":0,"width":100,"height":50}}],
            "splits":[]}],
          "agents":[
            {"terminal_id":"t1","agent_status":"blocked","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":true,"revision":9,"agent":"claude","state_change_seq":4},
            {"terminal_id":"t3","agent_status":"brand_new_status","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p3","focused":false,"revision":1,"agent":"codex"}
          ]
        }}}
    """.trimIndent()

    @Test
    fun decodesSnapshotAndOrdersPanesByLayout() {
        val result = herdrJson.parseToJsonElement(response).jsonObject["result"]!!.jsonObject
        val snapshot = herdrJson.decodeFromJsonElement<SessionSnapshot>(result["snapshot"]!!)
        assertEquals("0.7.2", snapshot.version)
        assertEquals(AgentStatus.BLOCKED, snapshot.workspaces.single().agentStatus)
        assertEquals(listOf("w1:p1", "w1:p2"), snapshot.panesOf("w1:t1").map { it.paneId })
        assertEquals("Refactor auth", snapshot.pane("w1:p1")!!.displayTitle)
        assertEquals("api", snapshot.pane("w1:p2")!!.displayTitle)
        // Unknown enum values are coerced instead of failing the whole snapshot.
        assertEquals(AgentStatus.UNKNOWN, snapshot.agents[1].agentStatus)
        assertEquals("w1:p1", snapshot.agentsIn("w1").first().paneId)
    }

    @Test
    fun tabLabelUsesPositionNotInternalNumber() {
        // herdr 0.9.3: tab id w1E:t6 sits second, so label is "2" while number is 6.
        val positional = TabInfo(tabId = "w1E:t6", workspaceId = "w1E", number = 6, label = "2")
        assertEquals("Tab 2", positional.displayLabel)
        assertEquals(false, positional.hasCustomLabel)
        val named = TabInfo(tabId = "w1:t1", workspaceId = "w1", number = 1, label = "logs")
        assertEquals("logs", named.displayLabel)
    }
    @Test
    fun filteringAgentsPreservesHerdrOrderAcrossStatusChanges() {
        val agents = listOf(
            AgentInfo(paneId = "w1:p8", workspaceId = "w1", tabId = "w1:t2", agentStatus = AgentStatus.IDLE),
            AgentInfo(paneId = "w2:p1", workspaceId = "w2", tabId = "w2:t1", agentStatus = AgentStatus.BLOCKED),
            AgentInfo(paneId = "w1:p2", workspaceId = "w1", tabId = "w1:t1", agentStatus = AgentStatus.WORKING),
            AgentInfo(paneId = "w1:p5", workspaceId = "w1", tabId = "w1:t1", agentStatus = AgentStatus.BLOCKED),
        )
        val snapshot = SessionSnapshot(agents = agents)
        assertEquals(listOf("w1:p8", "w1:p2", "w1:p5"), snapshot.agentsIn("w1").map { it.paneId })
        val next = snapshot.copy(agents = agents.map { it.copy(agentStatus = AgentStatus.DONE, stateChangeSeq = 99) })
        assertEquals(snapshot.agentsIn("w1").map { it.paneId }, next.agentsIn("w1").map { it.paneId })
    }

    @Test
    fun labelsUseServerOverridesAndOtherwiseMatchCanonicalState() {
        val agent = AgentInfo(paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", agentStatus = AgentStatus.BLOCKED)
        assertEquals("blocked", agent.statusLabel())
        assertEquals("approval required", agent.copy(stateLabels = mapOf("blocked" to "approval required")).statusLabel())
        assertEquals("blocked", agent.copy(stateLabels = mapOf("blocked" to "")).statusLabel())
    }

    @Test
    fun tabGroupsListEachPaneOnceInLayoutOrderWithinTheSpace() {
        fun pane(id: String, tab: String, ws: String = "w1") = PaneInfo(id, workspaceId = ws, tabId = tab)
        fun at(id: String, x: Int, y: Int) = PaneLayoutPane(id, rect = PaneLayoutRect(x, y, 10, 10))
        val snapshot = SessionSnapshot(
            tabs = listOf(TabInfo("w1:t1", "w1", label = "main"), TabInfo("w2:t1", "w2"), TabInfo("w1:t2", "w1", label = "logs")),
            panes = listOf(pane("w1:p3", "w1:t1"), pane("w1:p9", "w1:t2"), pane("w2:p1", "w2:t1", "w2"),
                pane("w1:p1", "w1:t1"), pane("w1:p2", "w1:t1")),
            layouts = listOf(PaneLayoutSnapshot("w1", "w1:t1", area = PaneLayoutRect(0, 0, 20, 20),
                panes = listOf(at("w1:p3", 10, 10), at("w1:p2", 10, 0), at("w1:p1", 0, 0)))),
        )
        val groups = snapshot.tabGroupsIn("w1")
        assertEquals(listOf("w1:t1", "w1:t2"), groups.map { it.tab.tabId })
        assertEquals(listOf(listOf("w1:p1", "w1:p2", "w1:p3"), listOf("w1:p9")), groups.map { g -> g.panes.map { it.paneId } })
        // The space list and the terminal's swipe/switcher walk the panes in the same order.
        assertEquals(snapshot.panesInLayoutOrder("w1"), groups.flatMap { it.panes })
    }

}
