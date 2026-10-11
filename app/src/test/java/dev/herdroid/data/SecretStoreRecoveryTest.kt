package dev.herdroid.data

import android.app.Application
import android.content.Context
import android.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.UnrecoverableKeyException
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SecretStoreRecoveryTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("test-vault", Context.MODE_PRIVATE)
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val store get() = SecretStore(prefs) { key }

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun absentCredentialDoesNotAccessOrCreateKey() {
        val vault = SecretStore(prefs) { error("No key access for an absent credential") }
        assertFalse(vault.contains("missing"))
        assertNull(vault.get("missing"))
    }

    @Test fun roundTripAndReadNeverRequestKeyCreation() {
        val modes = mutableListOf<Boolean>()
        val vault = SecretStore(prefs) { modes += it; key }
        vault.put("saved", "dummy-password")
        assertEquals("dummy-password", vault.get("saved"))
        assertEquals(listOf(true, false), modes)
        vault.put("empty", "")
        assertEquals("", vault.get("empty"))
    }

    @Test fun corruptAndWrongTypeEntriesArePreservedAndNotMistakenForAbsence() {
        val entries = listOf("not base64!", Base64.encodeToString(ByteArray(27), Base64.NO_WRAP))
        for (value in entries) {
            prefs.edit().putString("saved", value).commit()
            assertTrue(store.contains("saved"))
            assertUnreadable { store.get("saved") }
            assertEquals(value, prefs.getString("saved", null))
        }
        prefs.edit().putInt("saved", 7).commit()
        assertUnreadable { store.get("saved") }
        assertEquals(7, prefs.getInt("saved", 0))
    }

    @Test fun tamperedCiphertextIsNotRemovedOrOverwritten() {
        store.put("saved", "dummy-password")
        val payload = Base64.decode(prefs.getString("saved", null), Base64.NO_WRAP)
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        val corrupted = Base64.encodeToString(payload, Base64.NO_WRAP)
        prefs.edit().putString("saved", corrupted).commit()
        assertUnreadable { store.get("saved") }
        assertEquals(corrupted, prefs.getString("saved", null))
    }

    @Test fun missingOrUnavailableKeyHasSafeErrorAndDoesNotAttemptReplacement() {
        store.put("saved", "dummy-password")
        val before = prefs.all.toMap()
        val modes = mutableListOf<Boolean>()
        val marker = "provider-secret-marker"
        val unavailable = SecretStore(prefs) { create ->
            modes += create
            throw UnrecoverableKeyException(marker)
        }
        val failure = assertUnreadable { unavailable.get("saved") }
        assertFalse(failure.toString().contains(marker))
        assertNull(failure.cause)
        assertEquals(listOf(false), modes)
        assertEquals(before, prefs.all)
        assertTrue(unavailable.contains("saved"))
    }

    @Test fun wrongKeyFailsButExplicitReplacementCanRecover() {
        store.put("saved", "dummy-password")
        val wrong = SecretStore(prefs) { SecretKeySpec(ByteArray(32) { 99.toByte() }, "AES") }
        assertUnreadable { wrong.get("saved") }
        wrong.put("saved", "replacement-password")
        assertEquals("replacement-password", wrong.get("saved"))
    }

    private fun assertUnreadable(read: () -> String?): SecretReadException {
        try { read(); fail("Expected an unreadable saved credential") }
        catch (e: SecretReadException) { return e }
        error("unreachable")
    }
}
