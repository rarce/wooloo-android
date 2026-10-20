package dev.herdroid.connection

import dev.herdroid.herdr.AgentProvider
import dev.herdroid.herdr.AgentQuota
import dev.herdroid.herdr.AgentQuotaFiles
import dev.herdroid.herdr.AgentQuotaParser
import dev.herdroid.data.HostProfile
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One HTTP GET; tests replace it so they never reach Anthropic or OpenAI. */
fun interface QuotaHttp {
    suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String>

    companion object {
        private val client by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        val Default = QuotaHttp { url, headers ->
            val request = Request.Builder().url(url).header("Accept", "application/json").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                val body = it.body?.byteStream()?.use { input ->
                                    val output = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > 512 * 1024) throw IOException("Usage response is too large")
                                        output.write(buffer, 0, count)
                                    }
                                    output.toString(Charsets.UTF_8.name())
                                }.orEmpty()
                                it.code to body
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                })
            }
        }
    }
}

class QuotaException(message: String) : Exception(message)

/** UI leases can outlive a reconnect. Authorize the captured transport before any credential read. */
internal suspend fun readConsentedQuotaFiles(
    profile: HostProfile,
    canRead: (HostProfile) -> Boolean,
    read: suspend () -> AgentQuotaFiles,
): AgentQuotaFiles {
    if (!canRead(profile)) throw QuotaException("Agent quotas are not enabled for this machine")
    val files = read()
    if (!canRead(profile)) throw QuotaException("Agent quotas are not enabled for this machine")
    return files
}

/**
 * Reads the quotas of one machine's Claude Code and Codex sign-ins (wooloo's `AgentQuotaReader`).
 * It only reads their credentials and never refreshes them: a refresh rotates the refresh token and
 * would sign the agent out, so an expired token waits until the agent itself renews it.
 */
class AgentQuotaReader(
    private val readFiles: suspend (keychain: Boolean) -> AgentQuotaFiles,
    private val http: QuotaHttp = QuotaHttp.Default,
) {
    private class Cache {
        var claudeCredential: AgentQuotaParser.ClaudeCredential? = null
        var rejectedClaudeToken: String? = null
        var nextKeychainRead = 0L
        var keychainFailed = false
    }
    @Volatile private var cache = Cache()
    private val reads = Mutex()

    /** Detached cache objects prevent an older cancelled read from restoring cleared credentials. */
    fun reset() { cache = Cache() }

    /** A null quota means the agent is not set up on the machine. */
    suspend fun read(now: Long): Map<AgentProvider, Result<AgentQuota?>> = reads.withLock {
        val cache = cache
        val needsCredential = cache.claudeCredential?.let { it.isExpired(now) || it.token == cache.rejectedClaudeToken } ?: true
        val keychain = needsCredential && now >= cache.nextKeychainRead
        if (keychain) cache.nextKeychainRead = now + 5 * 60_000
        val files = try {
            readFiles(keychain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = Result.failure<AgentQuota?>(e)
            return@withLock mapOf(AgentProvider.CLAUDE to failure, AgentProvider.CODEX to failure)
        }
        if (files.claudeKeychainFailed) cache.nextKeychainRead = now + 15 * 60_000
        if (files.claudeKeychainFailed) cache.keychainFailed = true
        if (files.claudeCredentials != null) cache.keychainFailed = false
        coroutineScope {
            val claude = async { outcome { claude(files, now, cache) } }
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

    private suspend fun claude(files: AgentQuotaFiles, now: Long, cache: Cache): AgentQuota? {
        if (!files.claudeInstalled) return null
        files.claudeCredentials?.let(AgentQuotaParser::claudeCredential)?.let { cache.claudeCredential = it }
        val credential = cache.claudeCredential ?: throw QuotaException(
            if (cache.keychainFailed) "Claude Code's Keychain item is not readable over SSH"
            else "Not signed in to Claude Code"
        )
        if (credential.isExpired(now) || credential.token == cache.rejectedClaudeToken) {
            throw QuotaException("Claude Code's sign-in has expired; it renews when Claude Code runs")
        }
        val body = try {
            get("https://api.anthropic.com/api/oauth/usage", mapOf(
                "Authorization" to "Bearer ${credential.token}",
                "anthropic-beta" to "oauth-2025-04-20",
            ))
        } catch (e: Unauthorized) {
            cache.rejectedClaudeToken = credential.token
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
        currentCoroutineContext().ensureActive()
        val (status, body) = try {
            http.get(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // HTTP/header exceptions can include a rejected header value (the bearer token).
            throw QuotaException("Usage request failed. Check connectivity and try again later.")
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
    private var generation = 0L
    private var paused = false

    @Synchronized
    fun acquire() {
        users += 1
        startPolling()
    }

    private fun startPolling() {
        if (job != null || paused || users == 0) return
        val version = generation
        job = scope.launch {
            while (isActive) {
                lastFetch?.let { delay((intervalMs - (clock() - it)).coerceAtLeast(0)) }
                val results = reader.read(clock())
                synchronized(this@AgentQuotaMonitor) {
                    if (version != generation || paused || users == 0) return@launch
                    lastFetch = clock()
                    AgentProvider.entries.forEach { apply(it, results[it] ?: Result.success(null)) }
                    _loaded.value = true
                }
            }
        }
    }

    @Synchronized
    fun release() {
        users = maxOf(0, users - 1)
        if (users == 0) stopPolling()
    }

    private fun stopPolling() {
        generation++
        job?.cancel()
        job = null
        reader.reset()
    }

    /** Called synchronously before closing/replacing the SSH transport or disabling quotas. */
    @Synchronized
    fun invalidate() {
        paused = true
        clear()
    }

    /** Forget consent-scoped cached readings without reviving an invalid transport. */
    @Synchronized
    fun clear() {
        stopPolling()
        lastFetch = null
        _states.value = emptyMap()
        _loaded.value = false
    }

    @Synchronized
    fun resume() {
        paused = false
        startPolling()
    }

    @Synchronized
    fun apply(provider: AgentProvider, result: Result<AgentQuota?>) {
        val current = _states.value
        val next = result.fold(
            onSuccess = { quota -> quota?.let { State(it) } },
            onFailure = { State(current[provider]?.quota, it.message ?: "Couldn't read the quota") },
        )
        _states.value = if (next == null) current - provider else current + (provider to next)
    }
}
