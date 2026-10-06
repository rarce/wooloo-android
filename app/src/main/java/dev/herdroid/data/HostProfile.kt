package dev.herdroid.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod { KEY, PASSWORD }

/**
 * A saved SSH machine running a herdr server.
 *
 * Secrets are never stored here: passwords live in [SecretStore] (Keystore-encrypted)
 * and private keys live in [SshKeyManager].
 */
@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: AuthMethod = AuthMethod.KEY,
    val keyId: String? = null,
    /** herdr session name (`herdr --session <name>`). */
    val session: String = "default",
    /** Optional absolute path to the herdr binary on the remote machine. */
    val herdrPath: String? = null,
    val lastConnectedAt: Long? = null,
    val notifyWhenBlocked: Boolean = true,
    val notifyWhenDone: Boolean = true,
) {
    val displayName: String
        get() = label.ifBlank { if (session == "default") hostname else "$hostname/$session" }

    val target: String
        get() = buildString {
            if (username.isNotBlank()) append(username).append('@')
            append(hostname)
            if (port != 22) append(':').append(port)
        }
}
