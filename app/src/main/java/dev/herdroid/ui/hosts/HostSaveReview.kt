package dev.herdroid.ui.hosts

import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostLink
import dev.herdroid.data.HostProfile
import dev.herdroid.data.Transport

/** In-memory approval of one exact save, including its credential choices. Never persist this. */
internal data class HostSaveReview(
    val profile: HostProfile,
    val password: String,
    val accessSecret: String,
    val connect: Boolean,
) {
    override fun toString(): String = "HostSaveReview(profile=$profile, credentials=redacted, connect=$connect)"

    fun requiresConfirmation(existing: HostProfile?): Boolean = existing != null && (
        existing.transport != profile.transport ||
            normalizedHostname(existing) != normalizedHostname(profile) ||
            (profile.transport == Transport.SSH && existing.port != profile.port) ||
            existing.username != profile.username ||
            existing.accessClientId != profile.accessClientId ||
            existing.hostKeyFingerprints.toSet() != profile.hostKeyFingerprints.toSet() ||
            herdrPathChanged(existing)
        )

    /** The executable run on every connection, tried before the usual install locations. */
    fun herdrPathChanged(existing: HostProfile?): Boolean =
        existing != null && existing.herdrPath?.trim().orEmpty() != profile.herdrPath?.trim().orEmpty()

    private fun normalizedHostname(host: HostProfile): String =
        (if (host.transport == Transport.CLOUDFLARE) HostLink.normalizeHostname(host.hostname) else host.hostname.trim())
            .lowercase(java.util.Locale.ROOT)

    fun credentialsDescription(keyDescription: String? = null): String = buildString {
        append("Credentials for this destination: ")
        if (profile.auth == AuthMethod.KEY) {
            append("SSH key").append(keyDescription?.let { ": $it" }.orEmpty())
        } else {
            append(if (password.isEmpty()) "saved SSH password, if present (otherwise ask)" else "newly entered SSH password")
        }
        if (profile.accessClientId != null) {
            append("; ")
            append(if (accessSecret.isEmpty()) "saved Cloudflare Access secret, if present" else "newly entered Cloudflare Access secret")
        }
        append('.')
    }
}
