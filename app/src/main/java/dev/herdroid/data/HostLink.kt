package dev.herdroid.data

import java.net.URI
import java.net.URLDecoder

/**
 * A machine shared as `herdroid://add-host?...`, e.g. from the QR code xherdr shows:
 *
 * `herdroid://add-host?transport=cloudflare&host=<hostname>&user=<user>&session=<s>&label=<name>&herdr=<path>&fp=<SHA256:…>`
 *
 * `transport` is `ssh` (default, `port` allowed) or `cloudflare`; `fp` may repeat, one per host key type.
 * A link only prefills the host editor: the user still picks how to sign in, and nothing connects by itself.
 */
data class HostLink(
    val transport: Transport,
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    val session: String = "default",
    val label: String = "",
    val herdrPath: String? = null,
    val fingerprints: List<String> = emptyList(),
) {
    /** [base] with this link's machine applied; sign-in settings and notifications are kept. */
    fun applyTo(base: HostProfile): HostProfile = base.copy(
        label = label.ifBlank { base.label },
        hostname = hostname,
        port = port,
        username = username.ifBlank { base.username },
        transport = transport,
        session = session,
        herdrPath = herdrPath ?: base.herdrPath,
        hostKeyFingerprints = fingerprints.ifEmpty { base.hostKeyFingerprints },
    )

    /**
     * The saved profile this link refreshes, if any. Quick tunnel hostnames change on every
     * restart, so a Cloudflare machine is recognized by its name, user and session instead.
     */
    fun findExisting(hosts: List<HostProfile>): HostProfile? =
        if (transport == Transport.CLOUDFLARE) {
            if (label.isBlank() || username.isBlank()) null
            else hosts.firstOrNull {
                it.transport == Transport.CLOUDFLARE && it.label == label &&
                    it.username == username && it.session == session
            }
        } else {
            hosts.firstOrNull {
                it.transport == Transport.SSH && it.hostname.equals(hostname, ignoreCase = true) &&
                    it.port == port && it.username == username && it.session == session
            }
        }

    companion object {
        const val SCHEME = "herdroid"
        const val ACTION = "add-host"

        private val HOSTNAME = Regex("""[A-Za-z0-9._-]+|\[[0-9A-Fa-f:.]+]""")
        private val FINGERPRINT = Regex("""SHA256:[A-Za-z0-9+/]{43}=?""")

        /** Parses a `herdroid://add-host` link; null if it isn't one or has no usable host. */
        fun parse(link: String): HostLink? {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(ACTION, ignoreCase = true) && uri.authority?.equals(ACTION, ignoreCase = true) != true) {
                return null
            }
            val params = queryParams(uri.rawQuery.orEmpty())
            fun first(name: String) = params[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            val transport = when (first("transport")?.lowercase()) {
                null, "ssh" -> Transport.SSH
                "cloudflare" -> Transport.CLOUDFLARE
                else -> return null
            }
            val hostname = first("host")?.let(::normalizeHostname)?.takeIf { HOSTNAME.matches(it) } ?: return null
            val port = if (transport == Transport.SSH) {
                first("port")?.let { value -> value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null } ?: 22
            } else {
                22
            }
            return HostLink(
                transport = transport,
                hostname = hostname,
                port = port,
                username = first("user").orEmpty(),
                session = first("session") ?: "default",
                label = first("label").orEmpty(),
                herdrPath = first("herdr")?.takeIf { it.startsWith("/") },
                fingerprints = params["fp"].orEmpty().map { it.trim() }.filter { FINGERPRINT.matches(it) }.distinct(),
            )
        }

        /** `https://name.example.com/path` → `name.example.com`: what users paste for a tunnel. */
        fun normalizeHostname(input: String): String =
            input.trim()
                .replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')

        private fun queryParams(rawQuery: String): Map<String, List<String>> =
            rawQuery.split('&')
                .filter { it.isNotEmpty() }
                .map { part ->
                    val name = part.substringBefore('=')
                    val value = if ('=' in part) part.substringAfter('=') else ""
                    decode(name) to decode(value)
                }
                .groupBy({ it.first }, { it.second })

        // URLDecoder turns '+' into a space, which would break base64 fingerprints sent unencoded.
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }
}
