package dev.herdroid.connection

import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.AgentQuota
import dev.herdroid.herdr.AgentQuotaFiles
import dev.herdroid.herdr.AgentQuotaParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One HTTP GET; tests replace it so they never reach Anthropic or OpenAI. */
fun interface QuotaHttp {
    suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String>

    companion object {
        private val client by lazy {
            OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        }

        val Default = QuotaHttp { url, headers ->
            val request = Request.Builder().url(url).header("Accept", "application/json").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            runInterruptible(Dispatchers.IO) {
                client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            }
        }
    }
}

class QuotaException(message: String) : Exception(message)

/**
 * Reads the quotas of one machine's Claude Code and Codex sign-ins (wooloo's `AgentQuotaReader`).
 * It only reads their credentials and never refreshes them: a refresh rotates the refresh token and
 * would sign the agent out, so an expired token waits until the agent itself renews it.
 */
class AgentQuotaReader(
    private val readFiles: suspend (keychain: Boolean) -> AgentQuotaFiles,
    private val http: QuotaHttp = QuotaHttp.Default,
) {
    private var claudeCredential: AgentQuotaParser.ClaudeCredential? = null
    /** A token the usage endpoint rejected; it is not sent again. */
    private var rejectedClaudeToken: String? = null
    /** Keychain reads are spaced out, since each one can make macOS ask for permission. */
    private var nextKeychainRead = 0L
    /** The last Keychain read failed; later reads skip the Keychain for a while, so this explains why there's no sign-in. */
    private var keychainFailed = false

    /** A null quota means the agent is not set up on the machine. */
    suspend fun read(now: Long): Map<AgentProvider, Result<AgentQuota?>> {
        val needsCredential = claudeCredential?.let { it.isExpired(now) || it.token == rejectedClaudeToken } ?: true
        val keychain = needsCredential && now >= nextKeychainRead
        if (keychain) nextKeychainRead = now + 5 * 60_000
        val files = try {
            readFiles(keychain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = Result.failure<AgentQuota?>(e)
            return mapOf(AgentProvider.CLAUDE to failure, AgentProvider.CODEX to failure)
        }
        if (files.claudeKeychainFailed) nextKeychainRead = now + 15 * 60_000
        if (files.claudeKeychainFailed) keychainFailed = true
        if (files.claudeCredentials != null) keychainFailed = false
        return coroutineScope {
            val claude = async { outcome { claude(files, now) } }
            val codex = async { outcome { codex(files, now) } }
            mapOf(AgentProvider.CLAUDE to claude.await(), AgentProvider.CODEX to codex.await())
        }
    }

    private suspend fun outcome(body: suspend () -> AgentQuota?): Result<AgentQuota?> =
        try {
            Result.success(body())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun claude(files: AgentQuotaFiles, now: Long): AgentQuota? {
        if (!files.claudeInstalled) return null
        files.claudeCredentials?.let(AgentQuotaParser::claudeCredential)?.let { claudeCredential = it }
        val credential = claudeCredential ?: throw QuotaException(
            if (keychainFailed) "Claude Code's Keychain item is not readable over SSH"
            else "Not signed in to Claude Code"
        )
        if (credential.isExpired(now) || credential.token == rejectedClaudeToken) {
            throw QuotaException("Claude Code's sign-in has expired; it renews when Claude Code runs")
        }
        val body = try {
            get("https://api.anthropic.com/api/oauth/usage", mapOf(
                "Authorization" to "Bearer ${credential.token}",
                "anthropic-beta" to "oauth-2025-04-20",
            ))
        } catch (e: Unauthorized) {
            rejectedClaudeToken = credential.token
            throw QuotaException("Claude Code's sign-in was rejected; it renews when Claude Code runs")
        }
        val windows = AgentQuotaParser.claudeUsage(body)
        if (windows.isEmpty()) throw QuotaException("Unreadable Claude usage")
        return AgentQuota(windows, credential.plan, now)
    }

    /** Falls back to the newest session log when the usage endpoint can't be reached or the sign-in has expired. */
    private suspend fun codex(files: AgentQuotaFiles, now: Long): AgentQuota? {
        if (!files.codexInstalled) return null
        var failure: Exception = QuotaException("Not signed in to Codex with ChatGPT")
        val credential = files.codexAuth?.let(AgentQuotaParser::codexCredential)
        if (credential != null) {
            if (credential.expiresAt != null && credential.expiresAt <= now + 60_000) {
                failure = QuotaException("Codex's sign-in has expired; it renews when Codex runs")
            } else {
                try {
                    val headers = buildMap {
                        put("Authorization", "Bearer ${credential.token}")
                        put("User-Agent", "codex-cli")
                        credential.accountId?.let { put("ChatGPT-Account-Id", it) }
                    }
                    val (windows, plan) = AgentQuotaParser.codexUsage(get("https://chatgpt.com/backend-api/wham/usage", headers))
                    if (windows.isEmpty()) throw QuotaException("Unreadable Codex usage")
                    return AgentQuota(windows, plan, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Unauthorized) {
                    failure = QuotaException("Codex's sign-in was rejected; it renews when Codex runs")
                } catch (e: Exception) {
                    failure = e
                }
            }
        }
        files.codexLogLine?.let(AgentQuotaParser::codexLogLine)?.let { return it }
        throw failure
    }

    private class Unauthorized : Exception()

    private suspend fun get(url: String, headers: Map<String, String>): String {
        val (status, body) = try {
            http.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw QuotaException("Usage request failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return when (status) {
            200 -> body
            401, 403 -> throw Unauthorized()
            429 -> throw QuotaException("Usage requests are rate limited; retrying later")
            else -> throw QuotaException("Usage request failed (HTTP $status)")
        }
    }
}

/**
 * Polls one machine's agents every two minutes while a screen shows them. One per connection, so
 * leaving and reopening the spaces screen doesn't fetch again before the interval has passed.
 */
class AgentQuotaMonitor(
    private val scope: CoroutineScope,
    private val reader: AgentQuotaReader,
    private val intervalMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class State(val quota: AgentQuota? = null, val failure: String? = null)

    /** Agents without an entry are not set up on the machine. */
    private val _states = MutableStateFlow<Map<AgentProvider, State>>(emptyMap())
    val states: StateFlow<Map<AgentProvider, State>> = _states.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private var users = 0
    private var job: Job? = null
    private var lastFetch: Long? = null

    @Synchronized
    fun acquire() {
        users += 1
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                lastFetch?.let { delay(intervalMs - (clock() - it)) }
                lastFetch = clock()
                refresh()
            }
        }
    }

    @Synchronized
    fun release() {
        users = maxOf(0, users - 1)
        if (users == 0) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun refresh() {
        val results = reader.read(clock())
        AgentProvider.entries.forEach { apply(it, results[it] ?: Result.success(null)) }
        _loaded.value = true
    }

    fun apply(provider: AgentProvider, result: Result<AgentQuota?>) {
        val current = _states.value
        val next = result.fold(
            onSuccess = { quota -> quota?.let { State(it) } },
            // Keeps the last reading visible, marked as old, while the source fails.
            onFailure = { State(current[provider]?.quota, it.message ?: "Couldn't read the quota") },
        )
        _states.value = if (next == null) current - provider else current + (provider to next)
    }
}
