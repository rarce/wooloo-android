package dev.herdroid.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secret vault backed by an AES-GCM key that never leaves the Android Keystore.
 * Used for SSH passwords, Cloudflare Access secrets and private key material.
 */
class SecretStore internal constructor(
    private val prefs: SharedPreferences,
    private val keySource: (createIfMissing: Boolean) -> SecretKey,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("herdroid_secrets", Context.MODE_PRIVATE), ::androidKey,
    )

    /** Checks saved presence without invoking Android Keystore or decrypting credentials. */
    fun contains(name: String): Boolean = synchronized(vaultLock) { prefs.contains(name) }

    fun put(name: String, value: String) = transaction(mapOf(name to value)) { Unit }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource(true))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * Stage encryption first, then publish credentials and metadata while readers are excluded.
     * On a metadata failure restore the exact prior encrypted entries, without decrypting them.
     * This provides rollback for runtime failures, not a cross-file power-loss transaction.
     */
    fun <T> transaction(
        replacements: Map<String, String>,
        removals: Set<String> = emptySet(),
        publish: () -> T,
    ): T = synchronized(vaultLock) {
        require(replacements.keys.intersect(removals).isEmpty())
        val encrypted = replacements.mapValues { (_, value) -> encrypt(value) }
        val changed = replacements.keys + removals
        val previous = prefs.all.filterKeys { it in changed }
        try {
            val edit = prefs.edit()
            encrypted.forEach { (name, value) -> edit.putString(name, value) }
            removals.forEach(edit::remove)
            check(edit.commit()) { "Could not save secure storage" }
            publish()
        } catch (failure: Throwable) {
            val restore = prefs.edit()
            changed.forEach { name ->
                when (val value = previous[name]) {
                    null -> restore.remove(name)
                    is String -> restore.putString(name, value)
                    is Int -> restore.putInt(name, value)
                    is Long -> restore.putLong(name, value)
                    is Float -> restore.putFloat(name, value)
                    is Boolean -> restore.putBoolean(name, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        restore.putStringSet(name, value as Set<String>)
                    }
                    else -> error("Unsupported secure storage entry")
                }
            }
            try {
                check(restore.commit()) { "Could not restore secure storage" }
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    fun get(name: String): String? = synchronized(vaultLock) {
        val stored = prefs.getString(name, null) ?: return@synchronized null
        runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val data = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySource(false), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String) = transaction(emptyMap(), setOf(name)) { Unit }

    companion object {
        // Shared across vault instances using the app's same SharedPreferences file.
        private val vaultLock = Any()

        private fun androidKey(createIfMissing: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            check(createIfMissing) { "Secure storage key is missing" }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "herdroid.secrets.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12

        fun passwordKey(hostId: String) = "password:$hostId"
        fun accessSecretKey(hostId: String) = "cf-access-secret:$hostId"
        fun privateKey(keyId: String) = "key:$keyId"
    }
}
