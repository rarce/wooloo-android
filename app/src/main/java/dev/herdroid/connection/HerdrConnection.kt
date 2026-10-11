package dev.herdroid.connection

import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostProfile
import dev.herdroid.data.KnownHost
import dev.herdroid.data.SecretStore
import dev.herdroid.data.Transport
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import dev.herdroid.herdr.DirListing
import dev.herdroid.herdr.HerdrApi
import dev.herdroid.herdr.HerdrApiException
import dev.herdroid.herdr.HerdrNotFoundException
import dev.herdroid.herdr.RemoteCommand
import dev.herdroid.herdr.SessionSnapshot
import dev.herdroid.herdr.TerminalStream
import dev.herdroid.ssh.CloudflareTunnel
import dev.herdroid.ssh.CloudflareTunnelException
import dev.herdroid.ssh.HostKeyChallenge
import dev.herdroid.ssh.HostKeyRejectedException
import dev.herdroid.ssh.SshAuthException
import dev.herdroid.ssh.SshCredential
import dev.herdroid.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.min
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import dev.herdroid.ssh.interruptibleIo

enum class FailureKind { AUTH, HOST_KEY, HERDR_MISSING, SERVER_DOWN, OUTDATED, NETWORK, OTHER }

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data class Connecting(val step: String) : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Reconnecting(val attempt: Int, val reason: String, val retryAtMs: Long) : ConnectionStatus
    data class Failed(val kind: FailureKind, val message: String) : ConnectionStatus
}

sealed interface UserPrompt {
    data class HostKey(
        val challenge: HostKeyChallenge,
        val answer: CompletableDeferred<Boolean>,
    ) : UserPrompt

    data class Password(
        val target: String,
        val answer: CompletableDeferred<PasswordAnswer?>,
    ) : UserPrompt
}

data class PasswordAnswer(val password: String, val remember: Boolean)

/** An agent that changed into a state the user probably cares about. */
data class AgentAlert(
    val hostId: String,
    val hostName: String,
    val agent: AgentInfo,
    val workspaceLabel: String?,
)

/**
 * Everything about one live connection to one herdr server: SSH transport, cached session
 * snapshot kept fresh by event subscriptions, automatic reconnect, and agent alerts.
 *
 * Follows herdr's recommended client recovery model: events are invalidation signals and the
 * authoritative state always comes from `session.snapshot`.
 */
class HerdrConnection(
    initialProfile: HostProfile,
    private val deps: ConnectionDeps,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var profile: HostProfile = initialProfile
        private set

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _snapshot = MutableStateFlow<SessionSnapshot?>(null)
    val snapshot: StateFlow<SessionSnapshot?> = _snapshot.asStateFlow()

    private val _prompt = MutableStateFlow<UserPrompt?>(null)
    val prompt: StateFlow<UserPrompt?> = _prompt.asStateFlow()

    private val _alerts = MutableSharedFlow<AgentAlert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<AgentAlert> = _alerts.asSharedFlow()

    @Volatile
    private var ssh: SshSession? = null

    @Volatile
    var api: HerdrApi? = null
        private set

    /** The remote user's home directory, used to expand `~` (herdr's raw API wants absolute paths). */
    @Volatile
    var remoteHome: String? = null
        private set

    private var refreshJob: Job? = null
    private val lifecycle = RestartableJob(scope, interrupt = {
        _prompt.value?.let { cancelPrompt(it) }
        closeTransport()
    }) {
        try { connectionLoop() } finally {
            _prompt.value?.let { cancelPrompt(it) }
            closeTransport()
        }
    }
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val subscribedPanes = MutableStateFlow<Set<String>>(emptySet())

    val isLive: Boolean get() = _status.value == ConnectionStatus.Connected

    @Synchronized
    fun start() {
        if (refreshJob != null) return
        refreshJob = scope.launch { refreshWorker() }
        lifecycle.restart()
    }

    /** Retry immediately, after the previous connection loop has released its resources. */
    fun retryNow() { lifecycle.restart() }

    fun updateProfile(updated: HostProfile) {
        profile = updated
    }

    fun shutdown() {
        _prompt.value?.let { cancelPrompt(it) }
        scope.cancel()
        closeTransport()
        _status.value = ConnectionStatus.Idle
    }

    fun requestRefresh() {
        refreshRequests.trySend(Unit)
    }

    fun answer(prompt: UserPrompt, accepted: Boolean, password: PasswordAnswer? = null) {
        when (prompt) {
            is UserPrompt.HostKey -> prompt.answer.complete(accepted)
            is UserPrompt.Password -> prompt.answer.complete(if (accepted) password else null)
        }
        _prompt.compareAndSet(prompt, null)
    }

    /** Runs an API action, then refreshes the snapshot. */
    suspend fun <T> action(block: suspend HerdrApi.() -> T): Result<T> {
        val api = api ?: return Result.failure(IllegalStateException("Not connected"))
        val result = runCatching { api.block() }
        if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull()!!
        requestRefresh()
        return result
    }

    suspend fun openTerminal(
        paneId: String,
        cols: Int,
        rows: Int,
        takeover: Boolean,
        readOnly: Boolean,
        scope: CoroutineScope,
    ): TerminalStream {
        val ssh = ssh?.takeIf { it.isConnected } ?: throw IllegalStateException("Not connected")
        return TerminalStream.open(
            ssh = ssh,
            scope = scope,
            session = profile.session,
            herdrPath = profile.herdrPath,
            target = paneId,
            cols = cols,
            rows = rows,
            takeover = takeover,
            readOnly = readOnly,
        )
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun connectionLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                _status.value = ConnectionStatus.Connecting(
                    when {
                        attempt > 0 -> "Reconnecting…"
                        profile.transport == Transport.CLOUDFLARE -> "Opening Cloudflare tunnel to ${profile.hostname}…"
                        else -> "Opening SSH to ${profile.hostname}…"
                    }
                )
                val session = openSsh()
                ssh = session
                val api = HerdrApi(session, profile.session, profile.herdrPath)
                this.api = api
                if (remoteHome == null) remoteHome = try { readRemoteHome(session) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                _status.value = ConnectionStatus.Connecting("Loading herdr session…")
                applySnapshot(api.snapshot())
                _status.value = ConnectionStatus.Connected
                deps.hosts.markConnected(profile.id)
                attempt = 0
                runEventStreams(api, session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeTransport()
                val kind = classify(e)
                if (kind in setOf(
                        FailureKind.AUTH, FailureKind.HOST_KEY, FailureKind.HERDR_MISSING,
                        FailureKind.OUTDATED,
                    )
                ) {
                    _status.value = ConnectionStatus.Failed(kind, friendlyMessage(e))
                    return
                }
                attempt++
                val backoff = min(30_000L, 1_000L shl min(attempt - 1, 5))
                _status.value = ConnectionStatus.Reconnecting(
                    attempt = attempt,
                    reason = friendlyMessage(e),
                    retryAtMs = System.currentTimeMillis() + backoff,
                )
                delay(backoff)
                continue
            }
            // Event streams ended because the transport died: reconnect.
            closeTransport()
            attempt++
            _status.value = ConnectionStatus.Reconnecting(
                attempt, "Connection lost", System.currentTimeMillis() + 1_000
            )
            delay(1_000)
        }
    }

    /**
     * Keeps two subscriptions alive while SSH is up: lifecycle events (workspace/tab/pane
     * changes) and per-pane agent status changes. Each event just schedules a snapshot
     * refresh. A slow safety-net poll covers anything the events don't.
     */
    fun expandPath(path: String): String {
        val home = remoteHome ?: return path
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home.trimEnd('/') + path.substring(1)
            else -> path
        }
    }

    /** `/home/me/x` → `~/x`, for display; the inverse of [expandPath]. */
    fun abbreviatePath(path: String): String {
        val home = remoteHome?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
        return when {
            path == home -> "~"
            path.startsWith("$home/") -> "~" + path.substring(home.length)
            else -> path
        }
    }

    /** Subdirectories of [path] on the host (nearest existing ancestor if it doesn't exist). */
    suspend fun listDirectories(path: String): Result<DirListing> {
        val session = ssh?.takeIf { it.isConnected }
            ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            val channel = session.exec(RemoteCommand.listDirectories(expandPath(path)))
            val output = try {
                withTimeout(15_000) { runInterruptible(Dispatchers.IO) { channel.stdout.readBytes() } }
            } finally {
                channel.close()
            }
            DirListing.parse(output.toString(Charsets.UTF_8))?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Couldn't list $path"))
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Listing $path timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Uploads [bytes] as [name] into a private temp directory on the host; returns its path. */
    suspend fun uploadFile(name: String, bytes: ByteArray): Result<String> {
        val session = ssh?.takeIf { it.isConnected }
            ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            val channel = session.exec(RemoteCommand.uploadFile(name))
            val output = try {
                withTimeout(120_000) {
                    runInterruptible(Dispatchers.IO) {
                        channel.stdin.use { it.write(bytes) }
                        channel.stdout.readBytes()
                    }
                }
            } finally {
                channel.close()
            }
            output.toString(Charsets.UTF_8).trim().takeIf { it.startsWith("/") }?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Couldn't store $name on the host"))
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Uploading $name timed out"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun readRemoteHome(session: SshSession): String? =
        withTimeoutOrNull(15_000) {
            session.exec("/bin/sh -c 'printf %s \"\$HOME\"'").use { channel ->
                interruptibleIo { channel.stdout.readBytes().toString(Charsets.UTF_8).trim().takeIf { it.startsWith("/") } }
            }
        }

    private suspend fun runEventStreams(api: HerdrApi, session: SshSession) = coroutineScope {
        val poller = launch {
            while (isActive) {
                delay(SAFETY_POLL_MS)
                requestRefresh()
            }
        }
        val lifecycle = launch {
            while (isActive && session.isConnected) {
                runCatching {
                    api.subscribe(HerdrApi.LIFECYCLE_EVENTS).collect { requestRefresh() }
                }
                requestRefresh()
                delay(1_000)
            }
        }
        val agentStatus = launch {
            while (isActive && session.isConnected) {
                val panes = subscribedPanes.value
                if (panes.isEmpty()) {
                    delay(1_000)
                    continue
                }
                // Restart this stream whenever the set of agent panes changes.
                val collector = launch {
                    runCatching {
                        api.subscribeRaw(panes.map { paneId ->
                            buildJsonObject {
                                put("type", "pane.agent_status_changed")
                                put("pane_id", paneId)
                            }
                        }).collect { requestRefresh() }
                    }
                }
                val watcher = launch {
                    subscribedPanes.first { it != panes }
                    collector.cancel()
                }
                collector.join()
                watcher.cancel()
                delay(500)
            }
        }
        try {
            while (session.isConnected && isActive) delay(2_000)
        } finally {
            poller.cancel()
            lifecycle.cancel()
            agentStatus.cancel()
        }
    }

    private suspend fun refreshWorker() {
        for (unused in refreshRequests) {
            delay(REFRESH_DEBOUNCE_MS)
            val api = api ?: continue
            runCatching {
                val next = api.snapshot()
                // A refresh may finish after its transport has been replaced.
                if (this.api === api) applySnapshot(next)
            }
        }
    }

    private fun applySnapshot(next: SessionSnapshot) {
        val previous = _snapshot.value
        _snapshot.value = next
        subscribedPanes.value = next.agents.map { it.paneId }.toSet()
        if (previous != null) detectAlerts(previous, next)
    }

    private fun detectAlerts(previous: SessionSnapshot, next: SessionSnapshot) {
        val before = previous.agents.associateBy { it.paneId }
        for (agent in next.agents) {
            val old = before[agent.paneId]?.agentStatus ?: continue
            val now = agent.agentStatus
            val interesting = (now == AgentStatus.BLOCKED && old != AgentStatus.BLOCKED &&
                profile.notifyWhenBlocked) ||
                (now == AgentStatus.DONE && old == AgentStatus.WORKING && profile.notifyWhenDone)
            if (interesting) {
                val alert = AgentAlert(
                    hostId = profile.id,
                    hostName = profile.displayName,
                    agent = agent,
                    workspaceLabel = next.workspace(agent.workspaceId)?.label,
                )
                _alerts.tryEmit(alert)
                deps.notifier.agentAlert(alert)
            }
        }
    }

    private suspend fun openSsh(): SshSession {
        val tunnel = if (profile.transport == Transport.CLOUDFLARE) {
            CloudflareTunnel(
                hostname = profile.hostname,
                accessClientId = profile.accessClientId,
                accessClientSecret = deps.secrets.get(SecretStore.accessSecretKey(profile.id)),
            )
        } else {
            null
        }
        val session = SshSession(
            host = profile.hostname,
            port = profile.port,
            username = profile.username,
            knownFingerprint = { host, port -> deps.knownHosts.find(host, port)?.fingerprint },
            tunnel = tunnel,
            verifyHostKey = { challenge -> askHostKey(challenge) },
        )
        // Own the transport before authentication or any UI prompt can suspend.
        ssh = session
        try {
            session.connect(credential())
            return session
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    }

    private suspend fun credential(): SshCredential = when (profile.auth) {
        AuthMethod.KEY -> {
            val keyId = profile.keyId ?: throw SshAuthException("No SSH key selected for this host.")
            SshCredential.Key { deps.keys.keyProvider(keyId) }
        }
        AuthMethod.PASSWORD -> {
            val saved = deps.secrets.get(SecretStore.passwordKey(profile.id))
            if (saved != null) {
                SshCredential.Password(saved)
            } else {
                val answer = ask<PasswordAnswer> { UserPrompt.Password(profile.target, CompletableDeferred()) }
                    ?: throw SshAuthException("Password entry cancelled.")
                if (answer.remember) deps.secrets.put(SecretStore.passwordKey(profile.id), answer.password)
                SshCredential.Password(answer.password)
            }
        }
    }

    private suspend fun askHostKey(challenge: HostKeyChallenge): Boolean {
        // A pinned key (from wooloo's QR code) is trusted as if the user had accepted it.
        val accepted = challenge.fingerprint in profile.hostKeyFingerprints ||
            (ask<Boolean> { UserPrompt.HostKey(challenge, CompletableDeferred()) } ?: false)
        if (accepted) {
            deps.knownHosts.trust(
                KnownHost(challenge.host, challenge.port, challenge.keyType, challenge.fingerprint)
            )
        }
        return accepted
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> ask(make: () -> UserPrompt): T? {
        val prompt = make()
        _prompt.value = prompt
        deps.notifier.promptPending(profile)
        return try {
            withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
                when (prompt) {
                    is UserPrompt.HostKey -> prompt.answer.await()
                    is UserPrompt.Password -> prompt.answer.await()
                }
            } as T?
        } finally {
            cancelPrompt(prompt)
            _prompt.compareAndSet(prompt, null)
        }
    }

    private fun cancelPrompt(prompt: UserPrompt) {
        when (prompt) {
            is UserPrompt.HostKey -> prompt.answer.complete(false)
            is UserPrompt.Password -> prompt.answer.complete(null)
        }
    }

    private fun closeTransport() {
        api = null
        ssh?.close()
        ssh = null
    }

    private fun classify(e: Throwable): FailureKind {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            chain.any { it is SshAuthException } -> FailureKind.AUTH
            chain.any { it is CloudflareTunnelException && it.isAccessDenied } -> FailureKind.AUTH
            chain.any { it is HostKeyRejectedException } -> FailureKind.HOST_KEY
            chain.any { it is HerdrNotFoundException } -> FailureKind.HERDR_MISSING
            chain.any { it is HerdrApiException && it.code == "unsupported_version" } -> FailureKind.OUTDATED
            chain.any { it is HerdrApiException && it.code == "server_not_running" } -> FailureKind.SERVER_DOWN
            chain.any {
                it is UnknownHostException || it is ConnectException || it is SocketTimeoutException ||
                    it is NoRouteToHostException || it is java.io.IOException
            } -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
    }

    private fun friendlyMessage(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        chain.firstOrNull { it is CloudflareTunnelException }?.let { return it.message.orEmpty() }
        if (profile.transport == Transport.CLOUDFLARE) tunnelMessage(chain)?.let { return it }
        chain.firstOrNull { it is UnknownHostException }?.let {
            return "Can't resolve ${profile.hostname}. Check the hostname and your network."
        }
        chain.firstOrNull { it is ConnectException }?.let {
            return "Connection refused by ${profile.hostname}:${profile.port}."
        }
        chain.firstOrNull { it is SocketTimeoutException }?.let {
            return "Timed out reaching ${profile.hostname}."
        }
        return e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
    }

    private fun tunnelMessage(chain: List<Throwable>): String? {
        val host = profile.hostname
        val quick = host.endsWith(".trycloudflare.com", ignoreCase = true)
        return when {
            chain.any { it is UnknownHostException } && quick ->
                "Cloudflare tunnel $host no longer exists. Quick tunnel hostnames change every time " +
                    "Remote Access restarts: scan the new QR code in wooloo."
            chain.any { it is UnknownHostException } ->
                "Can't resolve $host. Check the tunnel hostname and your network."
            chain.any { it is SocketTimeoutException || it is ConnectException } ->
                "Cloudflare tunnel $host not reachable. Is Remote Access on in wooloo?"
            else -> null
        }
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 120L
        private const val SAFETY_POLL_MS = 20_000L
        private const val PROMPT_TIMEOUT_MS = 5 * 60_000L
    }
}
