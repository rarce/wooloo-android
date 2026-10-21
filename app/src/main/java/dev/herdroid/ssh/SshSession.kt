package dev.herdroid.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** What the user is asked when a server presents an unknown or changed host key. */
data class HostKeyChallenge(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    /** Fingerprint previously trusted for this host, if the key changed. */
    val previousFingerprint: String?,
) {
    /** `host:port`, without the port for a Cloudflare tunnel (it's always 443 there). */
    val displayTarget: String
        get() = if (port == CloudflareTunnelSocketFactory.TUNNEL_PORT) host else "$host:$port"
}

sealed interface SshCredential {
    data class Key(val provider: () -> KeyProvider) : SshCredential
    data class Password(val password: String) : SshCredential
}

class SshAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
class HostKeyRejectedException(message: String) : Exception(message)

/**
 * One authenticated SSH transport. herdr traffic is multiplexed over it as independent
 * exec channels: short-lived API requests, the event subscription and terminal streams.
 *
 * With a [tunnel], SSH runs inside a Cloudflare Tunnel WebSocket and [port] is ignored.
 */
class SshSession(
    private val host: String,
    port: Int,
    private val username: String,
    private val knownFingerprint: (host: String, port: Int) -> String?,
    private val tunnel: CloudflareTunnel? = null,
    private val verifyHostKey: suspend (HostKeyChallenge) -> Boolean,
) : RemoteShell {
    private val port = if (tunnel != null) CloudflareTunnelSocketFactory.TUNNEL_PORT else port

    private val client = SSHClient(AndroidConfig().apply {
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    })

    // Every exec channel counts toward sshd's MaxSessions (10 by default) until its close is
    // acknowledged, including one still closing after a pane switch. Stay below it: wait, not fail.
    private val channelLimit = Semaphore(MAX_EXEC_CHANNELS)

    private val disconnected = CompletableDeferred<Unit>()

    init {
        // sshj reports transport death (socket error, peer disconnect, our own abort) here.
        client.transport.disconnectListener = DisconnectListener { _, _ -> disconnected.complete(Unit) }
    }

    override val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    suspend fun connect(credential: SshCredential) {
        try {
            coroutineScope {
                val owner = currentCoroutineContext()
                var completed = false
                // Socket banner reads do not react to thread interruption. Closing
                // the raw socket on cancellation unblocks those reads as well.
                val cancellationCloser = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        if (!completed || !owner.isActive) close()
                    }
                }
                try {
                    interruptibleIo { connectBlocking(credential) }
                    completed = true
                } finally {
                    cancellationCloser.cancelAndJoin()
                }
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    private fun connectBlocking(credential: SshCredential) {
        client.connectTimeout = CONNECT_TIMEOUT_MS
        tunnel?.let { client.socketFactory = CloudflareTunnelSocketFactory(it) }
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = fingerprintOf(key)
                val known = knownFingerprint(host, this@SshSession.port)
                if (known == fingerprint) return true
                val challenge = HostKeyChallenge(
                    host = host,
                    port = this@SshSession.port,
                    keyType = KeyType.fromKey(key).toString(),
                    fingerprint = fingerprint,
                    previousFingerprint = known,
                )
                return runBlocking { verifyHostKey(challenge) }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                emptyList()
        })
        try {
            client.connect(host, port)
        } catch (e: Exception) {
            val rejected = generateSequence(e as Throwable) { it.cause }.any {
                it is HostKeyRejectedException ||
                    it is SSHException && it.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE
            }
            if (rejected) {
                throw HostKeyRejectedException("Host key for $host was not trusted")
            }
            throw e
        }
        client.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
        try {
            when (credential) {
                is SshCredential.Key -> client.auth(username, AuthPublickey(credential.provider()))
                is SshCredential.Password -> {
                    val finder = staticPassword(credential.password)
                    client.auth(
                        username,
                        AuthPassword(finder),
                        AuthKeyboardInteractive(PasswordResponseProvider(finder)),
                    )
                }
            }
        } catch (e: UserAuthException) {
            runCatching { client.disconnect() }
            throw SshAuthException(
                "Authentication failed for $username@$host. " +
                    if (credential is SshCredential.Key) "Is the public key in ~/.ssh/authorized_keys?"
                    else "Check the password.",
                e,
            )
        }
    }

    /** Opens a new exec channel. The caller owns the returned session and must close it. */
    override suspend fun exec(command: String): ExecChannel {
        channelLimit.acquire()
        return openExecChannel(
            command,
            createSession = {
                check(isConnected) { "SSH transport is not authenticated" }
                SessionChannel(client.connection, client.remoteCharset)
            },
            transportHealthy = ::isTransportHealthy,
            onReleased = channelLimit::release,
            abortTransport = ::abortTransport,
        )
    }

    /** Returns once the transport is gone: promptly through sshj's disconnect listener. */
    suspend fun awaitDisconnect() {
        while (isConnected) withTimeoutOrNull(DISCONNECT_POLL_MS) { disconnected.await() }
    }

    /**
     * Whether the peer still reads and answers this transport: a keepalive request with a reply.
     * Any answer counts (OpenSSH replies failure to unknown requests). A write stuck behind a
     * full socket blocks this probe too, so it times out and reports the transport unhealthy.
     */
    private suspend fun isTransportHealthy(): Boolean {
        if (!isConnected) return false
        val probe = channelReaper.async {
            val reply = client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0))
            runCatching { reply.tryRetrieve(HEALTH_PROBE_MS, TimeUnit.MILLISECONDS) != null }.getOrDefault(true)
        }
        val answered = withTimeoutOrNull(HEALTH_PROBE_MS + 500) { runCatching { probe.await() }.getOrDefault(false) }
        return answered == true && isConnected
    }

    private fun abortTransport() {
        runCatching { client.socket?.close() }
    }

    override fun close() {
        // SSHClient.disconnect can throw before SocketClient.disconnect runs.
        runCatching { client.socket?.close() }
        runCatching { client.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_SECONDS = 15
        private const val HEALTH_PROBE_MS = 5_000L
        private const val DISCONNECT_POLL_MS = 2_000L
        /** herdr API requests (4), two event subscriptions, a pane stream and one being replaced. */
        internal const val MAX_EXEC_CHANNELS = 8

        init {
            SecurityUtils.setRegisterBouncyCastle(true)
        }

        fun fingerprintOf(key: PublicKey): String {
            val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        private fun staticPassword(password: String) = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()
            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
    }
}

/**
 * Opens an exec channel without ever sacrificing the shared transport. The open runs detached: a
 * caller that gives up (a fast pane switch) leaves it to finish, and the channel is then closed
 * gracefully. A refused open (sshd MaxSessions) leaves nothing behind and is simply rethrown.
 * [onReleased] runs exactly once, when the channel no longer counts against the session.
 */
internal suspend fun openExecChannel(
    command: String,
    createSession: () -> SessionChannel,
    transportHealthy: suspend () -> Boolean = { false },
    onReleased: () -> Unit = {},
    stuckMs: Long = CHANNEL_STUCK_MS,
    abortTransport: () -> Unit,
): ExecChannel {
    val opening = channelReaper.async {
        val session = try {
            createSession().also { it.open() }
        } catch (e: Throwable) {
            onReleased()
            throw e
        }
        try {
            ExecChannel(session, session.exec(command), transportHealthy, onReleased, abortTransport)
        } catch (e: Throwable) {
            ExecChannel(session, session, transportHealthy, onReleased, abortTransport).closeBounded(stuckMs = stuckMs)
            throw e
        }
    }
    // A CHANNEL_OPEN write stuck on a dead socket would otherwise never settle.
    channelReaper.launch { abortIfStuck(opening, transportHealthy, abortTransport, stuckMs) }
    try {
        return opening.await()
    } catch (e: CancellationException) {
        // A late server confirmation still finds its channel attached, then closed.
        channelReaper.launch {
            runCatching { opening.await() }.getOrNull()?.closeBounded(terminateRemote = true, stuckMs = stuckMs)
        }
        throw e
    }
}

interface CommandChannel : AutoCloseable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isOpen: Boolean
    /** Breaks non-interruptible IO stuck on an unhealthy transport. */
    fun abort() {}
    /** Bounded probe of the transport under this channel. Unknown counts as unhealthy. */
    suspend fun isTransportHealthy(): Boolean = false
    /** Only explicitly cancelled long-lived commands need a remote termination signal. */
    fun close(terminateRemote: Boolean) = close()
}

class ExecChannel(
    private val session: Session,
    val command: Session.Command,
    private val transportHealthy: suspend () -> Boolean = { false },
    private val onReleased: () -> Unit = {},
    private val abortTransport: () -> Unit,
) : CommandChannel {
    private val closing = java.util.concurrent.atomic.AtomicBoolean()
    private val released = java.util.concurrent.atomic.AtomicBoolean()
    override val stdin get() = command.outputStream
    override val stdout get() = command.inputStream
    override val stderr get() = command.errorStream
    override val isOpen: Boolean get() = command.isOpen

    override fun close() = close(terminateRemote = false)

    override fun close(terminateRemote: Boolean) {
        if (!closing.compareAndSet(false, true)) return
        // Observe bridges do not read stdin. Stop this exec's process before waiting for
        // SSH close acknowledgement, without terminating the shared herdr server.
        if (terminateRemote && command.isOpen) {
            runCatching { command.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM) }
        }
        // Signal EOF first so a remote bridge waiting on stdin can exit and acknowledge close.
        // This runs inside closeBounded's IO deadline, including a stalled EOF write.
        runCatching { command.outputStream.close() }
        // sshj SessionChannel implements both objects. Closing it twice repeats
        // its acknowledgement wait when the first close was interrupted.
        runCatching { command.close() }
        if (command !== session) runCatching { session.close() }
        release()
    }

    override suspend fun isTransportHealthy() = transportHealthy()

    override fun abort() {
        abortTransport()
        session.notifyError(SSHException("Exec channel cleanup exceeded its deadline"))
        release()
    }

    private fun release() {
        if (released.compareAndSet(false, true)) onReleased()
    }
}
