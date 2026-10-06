package dev.herdroid.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

/** What the user is asked when a server presents an unknown or changed host key. */
data class HostKeyChallenge(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    /** Fingerprint previously trusted for this host, if the key changed. */
    val previousFingerprint: String?,
)

sealed interface SshCredential {
    data class Key(val provider: () -> KeyProvider) : SshCredential
    data class Password(val password: String) : SshCredential
}

class SshAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
class HostKeyRejectedException(message: String) : Exception(message)

/**
 * One authenticated SSH transport. herdr traffic is multiplexed over it as independent
 * exec channels: short-lived API requests, the event subscription and terminal streams.
 */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val knownFingerprint: (host: String, port: Int) -> String?,
    private val verifyHostKey: suspend (HostKeyChallenge) -> Boolean,
) {
    private val client = SSHClient(AndroidConfig().apply {
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    })

    val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    suspend fun connect(credential: SshCredential) = withContext(Dispatchers.IO) {
        client.connectTimeout = CONNECT_TIMEOUT_MS
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
    suspend fun exec(command: String): ExecChannel = withContext(Dispatchers.IO) {
        val session = client.startSession()
        try {
            ExecChannel(session, session.exec(command))
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
    }

    fun close() {
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

class ExecChannel(private val session: Session, val command: Session.Command) : AutoCloseable {
    val stdin get() = command.outputStream
    val stdout get() = command.inputStream
    val stderr get() = command.errorStream
    val isOpen: Boolean get() = command.isOpen

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
    }
}
