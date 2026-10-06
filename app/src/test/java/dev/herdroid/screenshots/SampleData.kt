package dev.herdroid.screenshots

import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.herdrJson
import kotlinx.serialization.json.decodeFromJsonElement

/** A realistic herdr session used to render screenshots without a server. */
object SampleData {
    val snapshot: SessionSnapshot by lazy {
        herdrJson.decodeFromJsonElement(herdrJson.parseToJsonElement(JSON))
    }

    private const val JSON = """
{"version":"0.9.3","protocol":22,"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1",
 "workspaces":[
  {"workspace_id":"w1","number":1,"label":"api","focused":true,"pane_count":3,"tab_count":2,"active_tab_id":"w1:t1","agent_status":"blocked"},
  {"workspace_id":"w2","number":2,"label":"web","focused":false,"pane_count":1,"tab_count":1,"active_tab_id":"w2:t1","agent_status":"done"},
  {"workspace_id":"w3","number":3,"label":"infra","focused":false,"pane_count":1,"tab_count":1,"active_tab_id":"w3:t1","agent_status":"idle"}],
 "tabs":[
  {"tab_id":"w1:t1","workspace_id":"w1","number":1,"label":"main","focused":true,"pane_count":3,"agent_status":"blocked"},
  {"tab_id":"w1:t2","workspace_id":"w1","number":2,"label":"logs","focused":false,"pane_count":1,"agent_status":"unknown"},
  {"tab_id":"w2:t1","workspace_id":"w2","number":1,"label":"","focused":true,"pane_count":1,"agent_status":"done"},
  {"tab_id":"w3:t1","workspace_id":"w3","number":1,"label":"","focused":true,"pane_count":1,"agent_status":"idle"}],
 "panes":[
  {"pane_id":"w1:p1","terminal_id":"t1","workspace_id":"w1","tab_id":"w1:t1","focused":true,"agent_status":"blocked","agent":"claude","revision":1,"cwd":"/srv/api","terminal_title_stripped":"Refactor auth middleware"},
  {"pane_id":"w1:p2","terminal_id":"t2","workspace_id":"w1","tab_id":"w1:t1","focused":false,"agent_status":"working","agent":"codex","revision":1,"cwd":"/srv/api","terminal_title_stripped":"Write integration tests"},
  {"pane_id":"w1:p3","terminal_id":"t3","workspace_id":"w1","tab_id":"w1:t1","focused":false,"agent_status":"unknown","revision":1,"cwd":"/srv/api","label":"server"},
  {"pane_id":"w1:p4","terminal_id":"t4","workspace_id":"w1","tab_id":"w1:t2","focused":false,"agent_status":"unknown","revision":1,"cwd":"/var/log"},
  {"pane_id":"w2:p1","terminal_id":"t5","workspace_id":"w2","tab_id":"w2:t1","focused":true,"agent_status":"done","agent":"claude","revision":1,"cwd":"/srv/web"},
  {"pane_id":"w3:p1","terminal_id":"t6","workspace_id":"w3","tab_id":"w3:t1","focused":true,"agent_status":"idle","agent":"pi","revision":1,"cwd":"/srv/infra"}],
 "layouts":[
  {"workspace_id":"w1","tab_id":"w1:t1","zoomed":false,"area":{"x":0,"y":0,"width":180,"height":48},"focused_pane_id":"w1:p1",
   "panes":[{"pane_id":"w1:p1","focused":true,"rect":{"x":0,"y":0,"width":100,"height":48}},
            {"pane_id":"w1:p2","focused":false,"rect":{"x":100,"y":0,"width":80,"height":30}},
            {"pane_id":"w1:p3","focused":false,"rect":{"x":100,"y":30,"width":80,"height":18}}],"splits":[]}],
 "agents":[
  {"terminal_id":"t1","agent_status":"blocked","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p1","focused":true,"revision":1,"agent":"claude","display_agent":"Claude","name":"auth-refactor","title":"Wants to run: npm run migrate","state_change_seq":9},
  {"terminal_id":"t2","agent_status":"working","workspace_id":"w1","tab_id":"w1:t1","pane_id":"w1:p2","focused":false,"revision":1,"agent":"codex","display_agent":"Codex","terminal_title_stripped":"Write integration tests","state_change_seq":7},
  {"terminal_id":"t5","agent_status":"done","workspace_id":"w2","tab_id":"w2:t1","pane_id":"w2:p1","focused":true,"revision":1,"agent":"claude","display_agent":"Claude","name":"reviewer","title":"Reviewed PR #142 — 3 comments","state_change_seq":8},
  {"terminal_id":"t6","agent_status":"idle","workspace_id":"w3","tab_id":"w3:t1","pane_id":"w3:p1","focused":true,"revision":1,"agent":"pi","display_agent":"Pi","state_change_seq":2}]
}"""

    /** An agent permission prompt, encoded the way herdr's renderer emits frames. */
    val agentScreen: String = buildString {
        val esc = "\u001b"
        fun at(row: Int, col: Int) = "$esc[${row};${col}H"
        append("$esc[?2026h$esc[?25l$esc[2J")
        append(at(1, 1)).append("$esc[0;38;5;245;49m~/srv/api$esc[0m  $esc[0;38;5;208;49m✻ Claude$esc[0m")
        append(at(3, 1)).append("$esc[0;1;39;49m> $esc[0mRefactor auth middleware to use the session store")
        append(at(5, 1)).append("$esc[0;38;5;114;49m⏺$esc[0m I'll update $esc[0;1;39;49msrc/middleware/auth.ts$esc[0m and add a migration.")
        append(at(7, 1)).append("$esc[0;38;5;114;49m⏺$esc[0m Update(src/middleware/auth.ts)")
        append(at(8, 3)).append("$esc[0;38;5;245;49m⎿$esc[0m  Updated with $esc[0;1;39;49m14$esc[0m additions and $esc[0;1;39;49m6$esc[0m removals")
        append(at(9, 6)).append("$esc[0;38;2;180;255;190;48;2;20;60;30m+  const session = await store.get(token);              $esc[0m")
        append(at(10, 6)).append("$esc[0;38;2;255;190;190;48;2;70;25;25m-  const session = cache[token];                         $esc[0m")
        append(at(12, 1)).append("$esc[0;38;5;75;49m╭──────────────────────────────────────────────────────╮$esc[0m")
        append(at(13, 1)).append("$esc[0;38;5;75;49m│$esc[0m $esc[0;1;39;49mBash command$esc[0m                                         $esc[0;38;5;75;49m│$esc[0m")
        append(at(14, 1)).append("$esc[0;38;5;75;49m│$esc[0m   npm run migrate -- --env staging                   $esc[0;38;5;75;49m│$esc[0m")
        append(at(15, 1)).append("$esc[0;38;5;75;49m│$esc[0m Do you want to proceed?                              $esc[0;38;5;75;49m│$esc[0m")
        append(at(16, 1)).append("$esc[0;38;5;75;49m│$esc[0m $esc[0;38;5;75;49m❯ 1. Yes$esc[0m                                             $esc[0;38;5;75;49m│$esc[0m")
        append(at(17, 1)).append("$esc[0;38;5;75;49m│$esc[0m   2. Yes, and don't ask again this session           $esc[0;38;5;75;49m│$esc[0m")
        append(at(18, 1)).append("$esc[0;38;5;75;49m│$esc[0m   3. No, and tell Claude what to do differently      $esc[0;38;5;75;49m│$esc[0m")
        append(at(19, 1)).append("$esc[0;38;5;75;49m╰──────────────────────────────────────────────────────╯$esc[0m")
        append(at(16, 5)).append("$esc[?25h$esc[?2026l")
    }
}
