package dev.herdroid.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SecretTransactionTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("txn", Context.MODE_PRIVATE)

    @Test fun removingMachineKeepsMetadataOnSecretFailureAndCanBeRetried() {
        val prefs = prefs()
        val unreliable = FailOncePreferences(prefs)
        val secrets = SecretStore(unreliable) { key }
        val hosts = HostRepository(File(RuntimeEnvironment.getApplication().filesDir, "delete-host.json"))
        val host = HostProfile(id = "delete-host", hostname = "example.test")
        hosts.upsert(host)
        secrets.put(SecretStore.passwordKey(host.id), "password")
        secrets.put(SecretStore.accessSecretKey(host.id), "access")
        unreliable.failNext = true
        try { hosts.deleteWithCredentials(host.id, secrets); fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertNotNull(hosts.get(host.id))
        assertEquals("password", secrets.get(SecretStore.passwordKey(host.id)))
        assertEquals("access", secrets.get(SecretStore.accessSecretKey(host.id)))
        hosts.deleteWithCredentials(host.id, secrets)
        assertNull(hosts.get(host.id))
        assertFalse(secrets.contains(SecretStore.passwordKey(host.id)))
        assertFalse(secrets.contains(SecretStore.accessSecretKey(host.id)))
    }

    @Test fun removingKeyRollsBackSecretsIfMetadataFailsAndWorksWithoutKeystore() {
        val prefs = prefs()
        val path = File(RuntimeEnvironment.getApplication().filesDir, "delete-key.json")
        val metadata = """[{"id":"key","name":"Fixture","type":"ed25519","publicKey":"ssh-ed25519 AAAA","createdAt":0}]"""
        path.writeText(metadata)
        // Deleting ciphertext must not require a usable Keystore.
        val secrets = SecretStore(prefs) { error("Keystore unavailable") }
        prefs.edit().putString(SecretStore.privateKey("key"), "opaque ciphertext")
            .putString("passphrase:key", "opaque passphrase").commit()
        val keys = SshKeyManager(path, secrets)
        path.delete()
        path.mkdirs()
        try { keys.delete("key"); fail("Expected metadata failure") }
        catch (_: IOException) { }
        assertNotNull(keys.get("key"))
        assertTrue(secrets.contains(SecretStore.privateKey("key")))
        assertTrue(secrets.contains("passphrase:key"))
        path.delete()
        keys.delete("key")
        assertNull(keys.get("key"))
        assertFalse(secrets.contains(SecretStore.privateKey("key")))
        assertFalse(secrets.contains("passphrase:key"))
    }

    @Test fun failedMetadataRestoresRawEntriesIncludingCorruptionAndWrongTypes() {
        val prefs = prefs()
        prefs.edit().putInt("password", 123).putString("access", "damaged encrypted entry")
            .putStringSet("remove", setOf("unexpected", "type")).commit()
        val previous = prefs.all
        val store = SecretStore(prefs) { key }
        val target = File(RuntimeEnvironment.getApplication().filesDir, "hosts.json").apply { mkdirs() }
        val hosts = HostRepository(File(target.parentFile, "metadata.json"))
        // Fail a real metadata write by turning its destination into a directory after loading.
        File(target.parentFile, "metadata.json").mkdirs()
        try {
            store.transaction(mapOf("password" to "new password", "access" to "new token"), setOf("remove", "missing")) {
                hosts.upsert(HostProfile(hostname = "example.test"))
            }
            fail("expected metadata failure")
        } catch (_: IOException) { }
        assertEquals(previous, prefs.all)
        assertTrue(hosts.hosts.value.isEmpty())
    }

    @Test fun secondEncryptionFailureDoesNotPublishAnyReplacementOrMetadata() {
        val prefs = prefs()
        val normal = SecretStore(prefs) { key }
        normal.put("password", "old password")
        normal.put("access", "old token")
        val previous = prefs.all
        val encryptions = AtomicInteger()
        val store = SecretStore(prefs) {
            if (encryptions.incrementAndGet() == 2) throw IOException("Keystore failed")
            key
        }
        var published = false
        try {
            store.transaction(linkedMapOf("password" to "new password", "access" to "new token")) {
                published = true
            }
            fail("expected encryption failure")
        } catch (_: IOException) { }
        assertFalse(published)
        assertEquals(previous, prefs.all)
        assertEquals("old password", normal.get("password"))
        assertEquals("old token", normal.get("access"))
    }

    @Test fun failedPreferenceCommitRestoresPutAndRemoveEvenIfMemoryAlreadyChanged() {
        val prefs = prefs()
        val normal = SecretStore(prefs) { key }
        normal.put("password", "old password")
        val previous = prefs.all
        val unreliable = FailOncePreferences(prefs)
        val store = SecretStore(unreliable) { key }
        unreliable.failNext = true
        try { store.put("password", "replacement"); fail("expected commit failure") }
        catch (_: IllegalStateException) { }
        assertEquals(previous, prefs.all)
        assertEquals("old password", normal.get("password"))
        unreliable.failNext = true
        try { store.remove("password"); fail("expected commit failure") }
        catch (_: IllegalStateException) { }
        assertEquals(previous, prefs.all)
        assertEquals("old password", normal.get("password"))
    }

    private class FailOncePreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val edit = delegate.edit()
            return object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?) = apply { edit.putString(key, value) }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { edit.putStringSet(key, values) }
                override fun putInt(key: String?, value: Int) = apply { edit.putInt(key, value) }
                override fun putLong(key: String?, value: Long) = apply { edit.putLong(key, value) }
                override fun putFloat(key: String?, value: Float) = apply { edit.putFloat(key, value) }
                override fun putBoolean(key: String?, value: Boolean) = apply { edit.putBoolean(key, value) }
                override fun remove(key: String?) = apply { edit.remove(key) }
                override fun clear() = apply { edit.clear() }
                override fun apply() { edit.apply() }
                override fun commit(): Boolean {
                    val result = edit.commit() // Android updates the memory map even when disk commit reports false.
                    if (!failNext) return result
                    failNext = false
                    return false
                }
            }
        }
    }

    @Test fun anotherVaultInstanceCannotReadCredentialsBeforeMetadataOutcome() = runBlocking {
        val prefs = prefs()
        val store = SecretStore(prefs) { key }
        val reader = SecretStore(prefs) { key }
        store.put("password", "old password")
        val publishing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        try {
            val update = async(Dispatchers.IO) {
                try {
                    store.transaction(mapOf("password" to "new password")) {
                        publishing.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        throw IOException("metadata unavailable")
                    }
                } catch (_: IOException) { }
            }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val read = async(Dispatchers.IO) {
                reading.countDown()
                reader.get("password").also { readFinished.countDown() }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertFalse(readFinished.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            withTimeout(5000) { update.await() }
            assertEquals("old password", withTimeout(5000) { read.await() })
        } finally { finish.countDown() }
    }
}
