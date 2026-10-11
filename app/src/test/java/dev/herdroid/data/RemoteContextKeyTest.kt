package dev.herdroid.data

import org.junit.Assert.*
import org.junit.Test

class RemoteContextKeyTest {
    private val profile = HostProfile(hostname = "SERVER.example", username = "alice")

    @Test fun changingDestinationOrNamespaceInvalidatesContext() {
        val before = profile.remoteContextKey()
        for (changed in listOf(
            profile.copy(hostname = "other.example"), profile.copy(port = 2222),
            profile.copy(username = "bob"), profile.copy(session = "work"),
            profile.copy(herdrPath = "/opt/herdr"), profile.copy(transport = Transport.CLOUDFLARE),
            profile.copy(keyId = "other-key"), profile.copy(auth = AuthMethod.PASSWORD),
            profile.copy(hostKeyFingerprints = listOf("new-key")), profile.copy(accessClientId = "new-client"),
        )) assertNotEquals(before, changed.remoteContextKey())
    }

    @Test fun metadataAndHostnameCaseDoNotInvalidateContext() {
        assertEquals(profile.remoteContextKey(), profile.copy(
            hostname = "server.EXAMPLE", label = "renamed", notifyWhenDone = false,
            lastConnectedAt = 100,
        ).remoteContextKey())
    }

    @Test fun cloudflareUsesTunnelPortNotTheIgnoredSshPort() {
        val tunnel = profile.copy(transport = Transport.CLOUDFLARE)
        assertEquals(tunnel.remoteContextKey(), tunnel.copy(port = 2222).remoteContextKey())
    }
}
