package dev.herdroid.data

/** Identity of the machine and herdr namespace represented by a cached snapshot. */
internal data class RemoteContextKey(
    val transport: Transport,
    val hostname: String,
    val port: Int,
    val username: String,
    val session: String,
    val herdrPath: String?,
    val auth: AuthMethod,
    val keyId: String?,
    val fingerprints: List<String>,
    val accessClientId: String?,
)

internal fun HostProfile.remoteContextKey() = RemoteContextKey(
    transport, hostname.lowercase(), if (transport == Transport.CLOUDFLARE) 443 else port,
    username, session, herdrPath, auth, keyId, hostKeyFingerprints.sorted(), accessClientId,
)
