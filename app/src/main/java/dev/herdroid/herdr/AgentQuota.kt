package dev.herdroid.herdr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.math.roundToInt

/** A coding agent whose subscription limits the spaces screen shows. */
enum class AgentProvider(val title: String) { CLAUDE("Claude"), CODEX("Codex") }

/** One usage limit of a subscription, for example Claude's five-hour session. Times are epoch milliseconds. */
data class QuotaWindow(
    val id: String,
    /** Short label, such as `5H` or `OPUS 7D`. */
    val label: String,
    val usedFraction: Double,
    val resetsAt: Long?,
) {
    /** The share used at [now]: a window whose reset has passed starts again from zero. */
    fun usedFraction(now: Long): Double = if (resetsAt != null && resetsAt <= now) 0.0 else usedFraction
}

data class AgentQuota(
    val windows: List<QuotaWindow>,
    val plan: String?,
    /** When the numbers were current. Readings from Codex's session log can be older than the fetch. */
    val capturedAt: Long,
    val fromLog: Boolean = false,
)

/**
 * Pure parsing of the agents' credentials, usage responses and session logs (wooloo's
 * `AgentQuotaParser`). The endpoints are the ones Claude Code (`/usage`) and the Codex CLI use;
 * neither is documented, so every field is optional and unknown keys are ignored.
 */
object AgentQuotaParser {
    data class ClaudeCredential(val token: String, val expiresAt: Long?, val plan: String?) {
        fun isExpired(now: Long) = expiresAt != null && expiresAt <= now + 60_000
        override fun toString() = "ClaudeCredential(expiresAt=$expiresAt, plan=$plan)"
    }

    data class CodexCredential(val token: String, val accountId: String?, val expiresAt: Long?) {
        override fun toString() = "CodexCredential(accountId=$accountId, expiresAt=$expiresAt)"
    }

    /** Claude Code's credentials, from `~/.claude/.credentials.json` or its macOS Keychain item. */
    fun claudeCredential(text: String): ClaudeCredential? {
        val oauth = obj(parse(text)?.get("claudeAiOauth")) ?: return null
        val token = string(oauth["accessToken"])?.takeIf { it.isNotEmpty() } ?: return null
        return ClaudeCredential(token, number(oauth["expiresAt"])?.toLong(), string(oauth["subscriptionType"]))
    }

    /** The ChatGPT sign-in in Codex's `auth.json`; null for an API-key login. */
    fun codexCredential(text: String): CodexCredential? {
        val tokens = obj(parse(text)?.get("tokens")) ?: return null
        val token = string(tokens["access_token"])?.takeIf { it.isNotEmpty() } ?: return null
        return CodexCredential(token, string(tokens["account_id"]), jwtExpiry(token))
    }

    /** The `exp` claim of a JWT, in epoch ms, without verifying it: it only decides whether to try the request. */
    fun jwtExpiry(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = runCatching { Base64.getUrlDecoder().decode(parts[1].trimEnd('=')) }.getOrNull() ?: return null
        val exp = number(parse(payload.toString(Charsets.UTF_8))?.get("exp")) ?: return null
        return (exp * 1000).toLong()
    }

    /** `GET api.anthropic.com/api/oauth/usage`: `utilization` is a percentage, `resets_at` ISO 8601. */
    fun claudeUsage(text: String): List<QuotaWindow> {
        val json = parse(text) ?: return emptyList()
        val windows = mutableListOf<QuotaWindow>()
        for ((key, label) in listOf("five_hour" to "5H", "seven_day" to "7D", "seven_day_opus" to "OPUS 7D",
            "seven_day_sonnet" to "SONNET 7D")) {
            val window = obj(json[key]) ?: continue
            val used = number(window["utilization"]) ?: continue
            windows += QuotaWindow(key, label, fraction(used), isoDate(window["resets_at"]))
        }
        // Model-scoped weekly limits; the account-wide ones repeat `five_hour` and `seven_day`.
        for (limit in (json["limits"] as? JsonArray).orEmpty().mapNotNull { obj(it) }) {
            if (string(limit["kind"]) != "weekly_scoped") continue
            val model = string(obj(obj(limit["scope"])?.get("model"))?.get("display_name"))?.trim()
            if (model.isNullOrEmpty() || model.lowercase() == "all models") continue
            val used = number(limit["percent"]) ?: continue
            val label = model.uppercase() + " 7D"
            if (windows.any { it.label == label }) continue
            windows += QuotaWindow("scoped-${model.lowercase()}", label, fraction(used), isoDate(limit["resets_at"]))
        }
        val extra = obj(json["extra_usage"])
        if (extra != null && (extra["is_enabled"] as? JsonPrimitive)?.booleanOrNull == true) {
            number(extra["utilization"])?.let { windows += QuotaWindow("extra_usage", "EXTRA", fraction(it), null) }
        }
        return windows
    }

    /** `GET chatgpt.com/backend-api/wham/usage`: windows carry `used_percent`, `limit_window_seconds`, `reset_at`. */
    fun codexUsage(text: String): Pair<List<QuotaWindow>, String?> {
        val json = parse(text) ?: return emptyList<QuotaWindow>() to null
        val windows = codexWindows(obj(json["rate_limit"]), prefix = "", id = "codex").toMutableList()
        for (extra in (json["additional_rate_limits"] as? JsonArray).orEmpty().mapNotNull { obj(it) }) {
            // `GPT-5.3-Codex-Spark` → `SPARK`.
            val name = string(extra["limit_name"])?.split('-')?.last()?.takeIf { it.isNotEmpty() } ?: continue
            windows += codexWindows(obj(extra["rate_limit"]), prefix = name.uppercase() + " ", id = "codex-${name.lowercase()}")
        }
        return windows to string(json["plan_type"])
    }

    /** A `token_count` event of a Codex rollout log, whose `payload.rate_limits` uses `window_minutes` and `resets_at`. */
    fun codexLogLine(line: String): AgentQuota? {
        if (!line.contains("\"rate_limits\"")) return null
        val json = parse(line) ?: return null
        val limits = obj(obj(json["payload"])?.get("rate_limits")) ?: return null
        val windows = codexWindows(limits, prefix = "", id = "codex", spanKey = "window_minutes", spanSeconds = 60.0,
            resetKey = "resets_at")
        if (windows.isEmpty()) return null
        return AgentQuota(windows, string(limits["plan_type"]), isoDate(json["timestamp"]) ?: 0L, fromLog = true)
    }

    private fun codexWindows(
        limits: JsonObject?,
        prefix: String,
        id: String,
        spanKey: String = "limit_window_seconds",
        spanSeconds: Double = 1.0,
        resetKey: String = "reset_at",
    ): List<QuotaWindow> {
        limits ?: return emptyList()
        return listOf("primary" to listOf("primary_window", "primary"), "secondary" to listOf("secondary_window", "secondary"))
            .mapNotNull { (name, keys) ->
                val window = keys.firstNotNullOfOrNull { obj(limits[it]) } ?: return@mapNotNull null
                val used = number(window["used_percent"]) ?: return@mapNotNull null
                val seconds = number(window[spanKey])?.times(spanSeconds)
                QuotaWindow("$id-$name", prefix + (seconds?.let(::spanLabel) ?: name.uppercase()), fraction(used),
                    number(window[resetKey])?.let { (it * 1000).toLong() })
            }
    }

    /** `18000` → `5H`, `604800` → `7D`. */
    fun spanLabel(seconds: Double): String {
        val hours = (seconds / 3600).roundToInt()
        return if (hours >= 24 && hours % 24 == 0) "${hours / 24}D" else "${maxOf(1, hours)}H"
    }

    private fun parse(text: String): JsonObject? = runCatching { herdrJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
    private fun obj(element: JsonElement?) = element as? JsonObject
    private fun string(element: JsonElement?) = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(element: JsonElement?) =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun fraction(percent: Double) = (percent / 100).coerceIn(0.0, 1.0)
    private fun isoDate(element: JsonElement?): Long? =
        string(element)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}

/** What a machine holds about its agents' sign-ins, gathered by one read-only shell script. */
data class AgentQuotaFiles(
    val claudeInstalled: Boolean = false,
    /** `~/.claude/.credentials.json`, or else Claude Code's Keychain item when it was asked for. */
    val claudeCredentials: String? = null,
    val claudeKeychainFailed: Boolean = false,
    val codexInstalled: Boolean = false,
    val codexAuth: String? = null,
    /** The last `token_count` event with rate limits in the newest Codex rollouts. */
    val codexLogLine: String? = null,
) {
    override fun toString() =
        "AgentQuotaFiles(claude=$claudeInstalled, codex=$codexInstalled, keychainFailed=$claudeKeychainFailed)"
}

object AgentQuotaProbe {
    /**
     * A POSIX sh script printing `key=value` lines, file contents in base64. [keychain] also reads
     * Claude Code's macOS Keychain item through `security` when there is no credentials file.
     */
    fun command(keychain: Boolean): String {
        val script = """
            LC_ALL=C; export LC_ALL
            b64() { base64 | tr -d '\n'; echo; }
            if [ -d "${'$'}HOME/.claude" ]; then
              echo "claude=1"
              if [ -r "${'$'}HOME/.claude/.credentials.json" ]; then
                printf 'claude_credentials='; b64 < "${'$'}HOME/.claude/.credentials.json"
              elif $keychain && command -v security >/dev/null 2>&1; then
                if item=$(security find-generic-password -s 'Claude Code-credentials' -w 2>/dev/null); then
                  printf 'claude_credentials='; printf '%s' "${'$'}item" | b64
                else
                  echo "claude_keychain=failed"
                fi
              fi
            fi
            codex="${'$'}{CODEX_HOME:-${'$'}HOME/.codex}"
            if [ -d "${'$'}codex" ]; then
              echo "codex=1"
              if [ -r "${'$'}codex/auth.json" ]; then printf 'codex_auth='; b64 < "${'$'}codex/auth.json"; fi
              for day in $(ls -d "${'$'}codex"/sessions/*/*/* 2>/dev/null | sort -r | head -n 3); do
                for file in $(ls -t "${'$'}day"/rollout-*.jsonl 2>/dev/null | head -n 3); do
                  line=$(tail -c 1048576 "${'$'}file" | grep -F '"rate_limits":{' | tail -n 1)
                  if [ -n "${'$'}line" ]; then printf 'codex_log='; printf '%s\n' "${'$'}line" | b64; break 2; fi
                done
              done
            fi
            exit 0
        """.trimIndent()
        return "/bin/sh -c " + RemoteCommand.shellQuote(script)
    }

    fun parse(output: String): AgentQuotaFiles {
        var files = AgentQuotaFiles()
        for (line in output.lineSequence()) {
            val equals = line.indexOf('=').takeIf { it >= 0 } ?: continue
            val value = line.substring(equals + 1).trim()
            val decoded = runCatching { Base64.getMimeDecoder().decode(value).toString(Charsets.UTF_8) }.getOrNull()
            files = when (line.substring(0, equals)) {
                "claude" -> files.copy(claudeInstalled = true)
                "claude_credentials" -> files.copy(claudeCredentials = decoded)
                "claude_keychain" -> files.copy(claudeKeychainFailed = true)
                "codex" -> files.copy(codexInstalled = true)
                "codex_auth" -> files.copy(codexAuth = decoded)
                "codex_log" -> files.copy(codexLogLine = decoded)
                else -> files
            }
        }
        return files
    }
}
