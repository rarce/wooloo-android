package dev.herdroid.ui.hosts

import dev.herdroid.data.AuthMethod
import dev.herdroid.data.HostLink
import dev.herdroid.data.HostProfile
import dev.herdroid.data.Transport
import org.junit.Assert.*
import org.junit.Test

class HostSaveReviewTest {
    private val original = HostProfile(id = "machine", label = "Mac", hostname = "old.trycloudflare.com",
        transport = Transport.CLOUDFLARE, username = "alice", auth = AuthMethod.PASSWORD,
        accessClientId = "client", hostKeyFingerprints = listOf("SHA256:old"))
    private fun review(profile: HostProfile, connect: Boolean = true) = HostSaveReview(profile, "", "", connect)

    @Test fun matchingQrCannotSilentlyRedirectSavedCredentials() {
        val link = HostLink.parse("herdroid://add-host?transport=cloudflare&host=evil.example&user=alice&label=Mac&fp=SHA256:${"A".repeat(43)}")!!
        assertEquals(original, link.findExisting(listOf(original)))
        val updated = link.applyTo(original)
        assertEquals(original.id, updated.id)
        assertTrue(review(updated).requiresConfirmation(original))
        assertTrue(review(updated, connect = false).requiresConfirmation(original))
        assertTrue(review(updated).credentialsDescription().contains("saved SSH password"))
        assertTrue(review(updated).credentialsDescription().contains("saved Cloudflare Access secret"))
    }

    @Test fun manualSecurityChangesRequireReview() {
        listOf(original.copy(hostname = "new.example"), original.copy(username = "bob"),
            original.copy(transport = Transport.SSH), original.copy(accessClientId = "other"),
            original.copy(hostKeyFingerprints = emptyList())).forEach {
            assertTrue(review(it).requiresConfirmation(original))
        }
        val ssh = original.copy(transport = Transport.SSH)
        assertTrue(review(ssh.copy(port = 2222)).requiresConfirmation(ssh))
    }

    @Test fun benignEditsAndEquivalentTrustDoNotPrompt() {
        assertFalse(review(original.copy(label = "Renamed", session = "work")).requiresConfirmation(original))
        assertFalse(review(original.copy(hostname = "OLD.TRYCLOUDFLARE.COM", port = 443)).requiresConfirmation(original))
        val twoKeys = original.copy(hostKeyFingerprints = listOf("a", "b"))
        assertFalse(review(twoKeys.copy(hostKeyFingerprints = listOf("b", "a", "a"))).requiresConfirmation(twoKeys))
        assertFalse(review(original).requiresConfirmation(null))
    }

}
