package dev.herdroid.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Semantic agent state reported by herdr. `done` means idle and not yet seen. */
@Serializable
enum class AgentStatus {
    @SerialName("idle") IDLE,
    @SerialName("working") WORKING,
    @SerialName("blocked") BLOCKED,
    @SerialName("done") DONE,
    @SerialName("unknown") UNKNOWN;

    /** Higher means the user should look sooner. */
    val attention: Int
        get() = when (this) {
            BLOCKED -> 4
            DONE -> 3
            WORKING -> 2
            IDLE -> 1
            UNKNOWN -> 0
        }
}

@Serializable
data class WorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String = "",
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
    val tokens: Map<String, String> = emptyMap(),
)

@Serializable
data class TabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
) {
    /** herdr labels unnamed tabs with their position ("1", "2"); `number` is an internal id, not shown. */
    val hasCustomLabel: Boolean get() = label.isNotBlank() && label.toIntOrNull() == null

    val displayLabel: String
        get() = if (hasCustomLabel) label else "Tab ${label.ifBlank { number.toString() }}"
}

@Serializable
data class PaneScrollInfo(
    @SerialName("offset_from_bottom") val offsetFromBottom: Long = 0,
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Long = 0,
    @SerialName("viewport_rows") val viewportRows: Int = 0,
)

@Serializable
data class PaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String = "",
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val label: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val scroll: PaneScrollInfo? = null,
    val revision: Long = 0,
) {
    val agentName: String? get() = displayAgent ?: agent

    /** Best human label for this pane, mirroring herdr's sidebar precedence. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: label?.takeIf { it.isNotBlank() }
            ?: terminalTitleStripped?.takeIf { it.isNotBlank() }
            ?: agentName
            ?: shortCwd
            ?: paneId

    val shortCwd: String?
        get() = (foregroundCwd ?: cwd)?.let { path ->
            path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        }

    fun statusLabel(): String =
        stateLabels[agentStatus.name.lowercase()] ?: agentStatus.name.lowercase()
}

@Serializable
data class AgentInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String = "",
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("agent_status") val agentStatus: AgentStatus = AgentStatus.UNKNOWN,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val name: String? = null,
    val title: String? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    val focused: Boolean = false,
    val revision: Long = 0,
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: displayAgent?.takeIf { it.isNotBlank() }
            ?: agent?.takeIf { it.isNotBlank() }
            ?: paneId

    val subtitle: String?
        get() = title?.takeIf { it.isNotBlank() } ?: terminalTitleStripped?.takeIf { it.isNotBlank() }

    fun statusLabel(): String =
        stateLabels[agentStatus.name.lowercase()] ?: when (agentStatus) {
            AgentStatus.BLOCKED -> "needs input"
            AgentStatus.DONE -> "done"
            AgentStatus.WORKING -> "working"
            AgentStatus.IDLE -> "idle"
            AgentStatus.UNKNOWN -> "unknown"
        }
}

@Serializable
data class PaneLayoutRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class PaneLayoutPane(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    val rect: PaneLayoutRect,
)

@Serializable
data class PaneLayoutSnapshot(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val zoomed: Boolean = false,
    val area: PaneLayoutRect,
    @SerialName("focused_pane_id") val focusedPaneId: String = "",
    val panes: List<PaneLayoutPane> = emptyList(),
)

@Serializable
data class SessionSnapshot(
    val version: String = "",
    val protocol: Int = 0,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    val layouts: List<PaneLayoutSnapshot> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
) {
    fun tabsOf(workspaceId: String) = tabs.filter { it.workspaceId == workspaceId }
    fun panesOf(tabId: String): List<PaneInfo> {
        val layout = layouts.firstOrNull { it.tabId == tabId }
        val panes = panes.filter { it.tabId == tabId }
        if (layout == null) return panes
        // Order panes visually: top-to-bottom, then left-to-right.
        val order = layout.panes.sortedWith(compareBy({ it.rect.y }, { it.rect.x }))
            .mapIndexed { index, pane -> pane.paneId to index }.toMap()
        return panes.sortedBy { order[it.paneId] ?: Int.MAX_VALUE }
    }
    fun pane(paneId: String) = panes.firstOrNull { it.paneId == paneId }
    fun agentFor(paneId: String) = agents.firstOrNull { it.paneId == paneId }
    fun workspace(id: String) = workspaces.firstOrNull { it.workspaceId == id }
    fun tab(id: String) = tabs.firstOrNull { it.tabId == id }
    fun layout(tabId: String) = layouts.firstOrNull { it.tabId == tabId }

    /** Agents sorted the way a phone user wants them: whoever needs you first. */
    val agentsByAttention: List<AgentInfo>
        get() = agents.sortedWith(
            compareByDescending<AgentInfo> { it.agentStatus.attention }
                .thenByDescending { it.stateChangeSeq }
        )
}
