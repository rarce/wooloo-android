package dev.herdroid.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuotaConsentTest {
    private val store get() = RuntimeEnvironment.getApplication().getSharedPreferences("quota-consent", Context.MODE_PRIVATE)
    private val host = HostProfile(id = "a", hostname = "host-a", username = "alice")

    @Before fun clear() { store.edit().clear().commit() }

    @Test fun consentDoesNotAuthorizeAnotherMachineAndSurvivesReload() {
        val prefs = UiPreferences(store)
        prefs.setAgentQuotas(host, true)
        assertTrue(quotaConsentKey(host) in prefs.agentQuotaContexts.value)
        assertFalse(quotaConsentKey(host.copy(id = "b", hostname = "host-b")) in prefs.agentQuotaContexts.value)
        assertEquals(prefs.agentQuotaContexts.value, UiPreferences(store).agentQuotaContexts.value)
        prefs.setAgentQuotas(host, false)
        assertTrue(UiPreferences(store).agentQuotaContexts.value.isEmpty())
    }

    @Test fun changingRemoteIdentityRequiresNewConsent() {
        val prefs = UiPreferences(store)
        prefs.setAgentQuotas(host, true)
        listOf(host.copy(hostname = "other"), host.copy(username = "bob"), host.copy(port = 2222),
            host.copy(session = "other"), host.copy(auth = AuthMethod.PASSWORD), host.copy(keyId = "other"),
            host.copy(transport = Transport.CLOUDFLARE), host.copy(accessClientId = "client"),
            host.copy(hostKeyFingerprints = listOf("SHA256:other")), host.copy(herdrPath = "/other/herdr"))
            .forEach { assertFalse(it.toString(), quotaConsentKey(it) in prefs.agentQuotaContexts.value) }
        assertTrue(quotaConsentKey(host.copy(label = "renamed", lastConnectedAt = 1L,
            notifyWhenDone = false, hostname = "HOST-A")) in prefs.agentQuotaContexts.value)
    }

    @Test fun formerGlobalOptInDoesNotAuthorizeAnyRemoteIdentity() {
        store.edit().putBoolean("agent_quotas", true).commit()
        assertTrue(UiPreferences(store).agentQuotaContexts.value.isEmpty())
    }
}
