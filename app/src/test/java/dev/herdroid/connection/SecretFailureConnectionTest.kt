package dev.herdroid.connection

import android.app.Application
import android.content.Context
import dev.herdroid.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecretFailureConnectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun corruptPasswordFailsBeforePromptOrSshAuthentication(): Unit = runBlocking {
        failsRecoverably(HostProfile(id = "password", hostname = "127.0.0.1", username = "test",
            auth = AuthMethod.PASSWORD), SecretStore.passwordKey("password"))
    }

    @Test fun corruptAccessTokenFailsBeforeOpeningTunnel(): Unit = runBlocking {
        failsRecoverably(HostProfile(id = "access", hostname = "never-connect.invalid", username = "test",
            transport = Transport.CLOUDFLARE, accessClientId = "dummy-client"), SecretStore.accessSecretKey("access"))
    }

    private suspend fun failsRecoverably(profile: HostProfile, entry: String) {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("herdroid_secrets", Context.MODE_PRIVATE)
        prefs.edit().putString(entry, "corrupt-payload").commit()
        val dir = temporary.newFolder()
        val secrets = SecretStore(app)
        val connection = HerdrConnection(profile, ConnectionDeps(
            HostRepository(File(dir, "hosts.json")), SshKeyManager(File(dir, "keys.json"), secrets),
            secrets, KnownHostsStore(File(dir, "known.json")), Notifier(app)))
        try {
            connection.start()
            val status = withTimeout(5_000) { connection.status.first { it is ConnectionStatus.Failed } } as ConnectionStatus.Failed
            assertEquals(FailureKind.SECURE_STORAGE, status.kind)
            assertNull("Unreadable password must not be silently treated as absent", connection.prompt.value)
            assertTrue(status.message.contains("Re-enter"))
            assertFalse(status.message.contains("corrupt-payload"))
            assertEquals("corrupt-payload", prefs.getString(entry, null))
            delay(100)
            assertEquals(status, connection.status.value)
        } finally { connection.shutdown(); prefs.edit().remove(entry).commit() }
    }
}
