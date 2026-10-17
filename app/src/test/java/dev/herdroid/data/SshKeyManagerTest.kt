package dev.herdroid.data

import android.content.Context
import java.io.File
import java.security.Security
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SshKeyManagerTest {
    @Test fun generatedAndImportedKeysSurviveReloadAndDeleteRemovesOnlyTheirSecrets() {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        val app = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(app.getSharedPreferences("keys", Context.MODE_PRIVATE)) {
            SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        }
        val file = File(app.filesDir, "keys.json")
        val manager = SshKeyManager(file, secrets)
        val generated = manager.generateEd25519("")
        assertEquals("wooloo-mobile", generated.name)
        assertTrue(generated.fingerprint.startsWith("SHA256:"))
        val pem = secrets.get(SecretStore.privateKey(generated.id))!!
        val imported = manager.import("copy", pem, null)
        assertEquals(generated.fingerprint, imported.fingerprint)
        assertEquals(manager.keyProvider(generated.id).public, manager.keyProvider(imported.id).public)
        val reloaded = SshKeyManager(file, secrets)
        assertEquals(listOf(generated, imported), reloaded.keys.value)
        assertEquals(imported, reloaded.get(imported.id))
        reloaded.delete(imported.id)
        assertNull(reloaded.get(imported.id))
        assertNull(secrets.get(SecretStore.privateKey(imported.id)))
        assertNotNull(secrets.get(SecretStore.privateKey(generated.id)))
        assertEquals(listOf(generated), SshKeyManager(file, secrets).keys.value)
    }

    @Test fun invalidImportDoesNotPersistKeyAndMissingKeyFailsClearly() {
        val app = RuntimeEnvironment.getApplication()
        val secrets = SecretStore(app.getSharedPreferences("invalid", Context.MODE_PRIVATE)) {
            SecretKeySpec(ByteArray(32), "AES")
        }
        val file = File(app.filesDir, "invalid.json")
        val manager = SshKeyManager(file, secrets)
        assertThrows(IllegalArgumentException::class.java) { manager.import("bad", "not a key", "passphrase") }
        assertTrue(manager.keys.value.isEmpty())
        assertFalse(file.exists())
        assertThrows(IllegalStateException::class.java) { manager.keyProvider("missing") }
        assertEquals("", SshKeyInfo("id", "bad", "unknown", "invalid", 0).fingerprint)
    }
}
