package dev.herdroid.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod { KEY, PASSWORD }

/** How SSH reaches the machine: a direct TCP port, or a Cloudflare Tunnel WebSocket. */
@Serializable
enum class Transport { SSH, CLOUDFLARE }

/**
 * A saved SSH machine running a herdr server.
 *
 * Secrets are never stored here: passwords and the Cloudflare Access client secret live in
 * [SecretStore] (Keystore-encrypted) and private keys live in [SshKeyManager].
 */
@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    /** SSH host, or the tunnel's public hostname for [Transport.CLOUDFLARE]. */
    val hostname: String = "",
    /** Only used for [Transport.SSH]. */
    val port: Int = 22,
    val transport: Transport = Transport.SSH,
    /** Cloudflare Access service token client ID, when the tunnel hostname is behind Access. */
    val accessClientId: String? = null,
    /** Host keys trusted without asking (`SHA256:…`), e.g. from wooloo's QR code. */
    val hostKeyFingerprints: List<String> = emptyList(),
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
            if (transport == Transport.SSH && port != 22) append(':').append(port)
        }
}
