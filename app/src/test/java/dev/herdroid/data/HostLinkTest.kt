package dev.herdroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HostLinkTest {
    private val fp1 = "SHA256:" + "A".repeat(42) + "+"
    private val fp2 = "SHA256:" + "b".repeat(42) + "/"

    @Test
    fun parsesCloudflareLink() {
        val link = HostLink.parse(
            "herdroid://add-host?transport=cloudflare&host=wanna-cricket.trycloudflare.com&user=roberto" +
                "&session=work&label=Roberto%27s%20Mac&herdr=%2Fopt%2Fhomebrew%2Fbin%2Fherdr" +
                "&fp=${fp1.replace("+", "%2B")}&fp=$fp2"
        )!!
        assertEquals(Transport.CLOUDFLARE, link.transport)
        assertEquals("wanna-cricket.trycloudflare.com", link.hostname)
        assertEquals("roberto", link.username)
        assertEquals("work", link.session)
        assertEquals("Roberto's Mac", link.label)
        assertEquals("/opt/homebrew/bin/herdr", link.herdrPath)
        assertEquals(listOf(fp1, fp2), link.fingerprints)
    }

    @Test
    fun literalPlusInFingerprintIsKept() {
        val link = HostLink.parse("herdroid://add-host?transport=cloudflare&host=a.example.com&fp=$fp1")!!
        assertEquals(listOf(fp1), link.fingerprints)
    }

    @Test
    fun macQrRefreshesTunnelWithoutLosingCredentialsOrEscapedSession() {
        // URLComponents output from wooloo's RemoteAccessLink: slashes/colons stay literal,
        // literal plus signs are escaped, and names may contain apostrophes and Unicode.
        val raw = "herdroid://add-host?transport=cloudflare&host=new-name.trycloudflare.com" +
            "&user=roberto&session=review%20%2B%20m%C3%B3vil&label=Roberto's%20Mac" +
            "&herdr=/Users/roberto/Tools%20%2B%20Apps/herdr&fp=SHA256:${"A".repeat(42)}%2B&fp=$fp2"
        val link = HostLink.parse(raw)!!
        val saved = HostProfile(label = "Roberto's Mac", hostname = "old-name.trycloudflare.com",
            username = "roberto", session = "review + móvil", transport = Transport.CLOUDFLARE,
            auth = AuthMethod.KEY, keyId = "authorized-phone-key")
        assertSame(saved, link.findExisting(listOf(saved)))
        val refreshed = link.applyTo(saved)
        assertEquals(saved.id, refreshed.id)
        assertEquals("authorized-phone-key", refreshed.keyId)
        assertEquals("review + móvil", refreshed.session)
        assertEquals("/Users/roberto/Tools + Apps/herdr", refreshed.herdrPath)
        assertEquals(listOf(fp1, fp2), refreshed.hostKeyFingerprints)
    }

    @Test
    fun defaultsToSshWithPort() {
        val link = HostLink.parse("herdroid://add-host?host=devbox.local&user=me&port=2222")!!
        assertEquals(Transport.SSH, link.transport)
        assertEquals(2222, link.port)
        assertEquals("default", link.session)
        assertEquals("", link.label)
        assertNull(link.herdrPath)
        assertEquals(emptyList<String>(), link.fingerprints)
    }

    @Test
    fun hostnameIsNormalized() {
        val link = HostLink.parse("herdroid://add-host?transport=cloudflare&host=https%3A%2F%2Fx.example.com%2F")!!
        assertEquals("x.example.com", link.hostname)
        assertEquals("x.example.com", HostLink.normalizeHostname(" https://x.example.com/path?q "))
    }

    @Test
    fun rejectsInvalidLinks() {
        assertNull(HostLink.parse("https://add-host?host=x"))
        assertNull(HostLink.parse("herdroid://other?host=x"))
        assertNull(HostLink.parse("herdroid://add-host?user=me"))
        assertNull(HostLink.parse("herdroid://add-host?host=bad%20host"))
        assertNull(HostLink.parse("herdroid://add-host?host=x&port=70000"))
        assertNull(HostLink.parse("herdroid://add-host?host=x&transport=telnet"))
    }

    @Test
    fun dropsMalformedFingerprintsAndRelativeHerdrPath() {
        val link = HostLink.parse("herdroid://add-host?host=x&fp=MD5:aa&fp=$fp2&fp=$fp2&herdr=bin%2Fherdr")!!
        assertEquals(listOf(fp2), link.fingerprints)
        assertNull(link.herdrPath)
    }

    @Test
    fun appliesOverExistingProfileKeepingSignIn() {
        val existing = HostProfile(
            label = "Mac", hostname = "old.trycloudflare.com", username = "me", transport = Transport.CLOUDFLARE,
            auth = AuthMethod.PASSWORD, keyId = "k", accessClientId = "id", notifyWhenDone = false,
        )
        val link = HostLink(Transport.CLOUDFLARE, "new.trycloudflare.com", username = "me", label = "Mac",
            fingerprints = listOf(fp1))
        val updated = link.applyTo(existing)
        assertEquals(existing.id, updated.id)
        assertEquals("new.trycloudflare.com", updated.hostname)
        assertEquals(listOf(fp1), updated.hostKeyFingerprints)
        assertEquals(AuthMethod.PASSWORD, updated.auth)
        assertEquals("k", updated.keyId)
        assertEquals("id", updated.accessClientId)
        assertEquals(false, updated.notifyWhenDone)
    }

    @Test
    fun findsCloudflareMachineByNameUserAndSession() {
        val mac = HostProfile(label = "Mac", hostname = "old.trycloudflare.com", username = "me",
            transport = Transport.CLOUDFLARE)
        val other = HostProfile(label = "Mac", hostname = "mac.local", username = "me")
        val hosts = listOf(other, mac)
        val link = HostLink(Transport.CLOUDFLARE, "new.trycloudflare.com", username = "me", label = "Mac")
        assertSame(mac, link.findExisting(hosts))
        assertNull(link.copy(session = "work").findExisting(hosts))
        assertNull(link.copy(label = "").findExisting(hosts))
        assertSame(other, HostLink(Transport.SSH, "MAC.local", username = "me").findExisting(hosts))
    }

    @Test
    fun cloudflareTargetHasNoPort() {
        val profile = HostProfile(hostname = "x.example.com", port = 2222, username = "me", transport = Transport.CLOUDFLARE)
        assertEquals("me@x.example.com", profile.target)
    }

    @Test
    fun oldProfileJsonStillDecodes() {
        val json = """[{"id":"1","hostname":"h","port":22,"username":"u"}]"""
        val profile = storeJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(HostProfile.serializer()), json
        ).single()
        assertEquals(Transport.SSH, profile.transport)
        assertNull(profile.accessClientId)
        assertEquals(emptyList<String>(), profile.hostKeyFingerprints)
    }
}
