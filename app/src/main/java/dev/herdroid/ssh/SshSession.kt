package dev.herdroid.ssh

import kotlinx.coroutines.*
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
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
) {
    private val port = if (tunnel != null) CloudflareTunnelSocketFactory.TUNNEL_PORT else port

    private val client = SSHClient(AndroidConfig().apply {
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    })

    val isConnected: Boolean
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
            val root = generateSequence(e as Throwable) { it.cause }.last()
            if (root is HostKeyRejectedException || e.message?.contains("HOST_KEY_NOT_VERIFIABLE") == true) {
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
    suspend fun exec(command: String): ExecChannel {
        var opened: ExecChannel? = null
        try {
            return interruptibleIo {
                val session = client.startSession()
                try {
                    ExecChannel(session, session.exec(command)).also { opened = it }
                } catch (e: Throwable) {
                    runCatching { session.close() }
                    throw e
                }
            }
        } catch (e: Throwable) {
            // Cancellation can win while the result is dispatched back to the caller.
            opened?.close()
            throw e
        }
    }

    fun close() {
        // SSHClient.disconnect can throw before SocketClient.disconnect runs.
        runCatching { client.socket?.close() }
        runCatching { client.disconnect() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val KEEPALIVE_SECONDS = 15

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

interface CommandChannel : AutoCloseable {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isOpen: Boolean
}

class ExecChannel(private val session: Session, val command: Session.Command) : CommandChannel {
    override val stdin get() = command.outputStream
    override val stdout get() = command.inputStream
    override val stderr get() = command.errorStream
    override val isOpen: Boolean get() = command.isOpen

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
    }
}
