package dev.herdroid.data

import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile

@Serializable
data class SshKeyInfo(
    val id: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val createdAt: Long,
    val encrypted: Boolean = false,
) {
    val fingerprint: String
        get() = runCatching {
            val blob = Base64.getDecoder().decode(publicKey.split(" ")[1])
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }.getOrDefault("")
}

/**
 * Manages SSH identities. Private key material is kept in [SecretStore];
 * only metadata and public keys are stored in plain JSON.
 */
class SshKeyManager(private val file: File, private val secrets: SecretStore) {
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<List<SshKeyInfo>> = _keys.asStateFlow()

    fun get(id: String): SshKeyInfo? = _keys.value.firstOrNull { it.id == id }

    /** Generates a fresh Ed25519 identity, the modern default for OpenSSH. */
    fun generateEd25519(name: String): SshKeyInfo {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters
        val comment = name.ifBlank { "herdroid" }
        val pem = OpenSshEd25519.encodePrivateKey(priv.encoded, pub.encoded, comment)
        val publicLine = "ssh-ed25519 " +
            Base64.getEncoder().encodeToString(OpenSshEd25519.publicBlob(pub.encoded)) + " " + comment
        val info = SshKeyInfo(
            id = UUID.randomUUID().toString(),
            name = comment,
            type = "ed25519",
            publicKey = publicLine,
            createdAt = System.currentTimeMillis(),
        )
        secrets.put(SecretStore.privateKey(info.id), pem)
        add(info)
        return info
    }

    /**
     * Imports a pasted private key (OpenSSH, PEM or PKCS#8). The key is validated by loading it,
     * which also yields the public half for display.
     */
    fun import(name: String, privateKey: String, passphrase: String?): SshKeyInfo {
        val text = privateKey.trim() + "\n"
        val provider = loadProvider(text, passphrase)
        val publicKey = provider.public
        val type = KeyType.fromKey(publicKey)
        val blob = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        val publicLine = "$type " + Base64.getEncoder().encodeToString(blob) +
            " " + name.ifBlank { "imported" }
        val info = SshKeyInfo(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Imported key" },
            type = type.toString().removePrefix("ssh-"),
            publicKey = publicLine,
            createdAt = System.currentTimeMillis(),
            encrypted = !passphrase.isNullOrEmpty(),
        )
        secrets.put(SecretStore.privateKey(info.id), text)
        if (!passphrase.isNullOrEmpty()) secrets.put(passphraseKey(info.id), passphrase)
        add(info)
        return info
    }

    fun delete(id: String) {
        secrets.remove(SecretStore.privateKey(id))
        secrets.remove(passphraseKey(id))
        _keys.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun keyProvider(id: String): KeyProvider {
        val text = secrets.get(SecretStore.privateKey(id))
            ?: error("Private key is missing from secure storage")
        return loadProvider(text, secrets.get(passphraseKey(id)))
    }

    private fun loadProvider(text: String, passphrase: String?): KeyProvider {
        val finder = passphrase?.takeIf { it.isNotEmpty() }
            ?.let { PasswordUtils.createOneOff(it.toCharArray()) }
        val candidates = listOf(
            { OpenSSHKeyV1KeyFile() },
            { OpenSSHKeyFile() },
            { PKCS8KeyFile() },
        )
        var lastError: Throwable? = null
        for (factory in candidates) {
            val file = factory()
            try {
                if (finder != null) file.init(text, null, finder) else file.init(text, null)
                file.public // force parse
                return file
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalArgumentException(
            "Unsupported or invalid private key" + (lastError?.message?.let { ": $it" } ?: ""),
            lastError,
        )
    }

    private fun add(info: SshKeyInfo) {
        _keys.update { it + info }
        persist()
    }

    private fun load(): List<SshKeyInfo> = runCatching {
        if (!file.exists()) emptyList()
        else storeJson.decodeFromString(ListSerializer(SshKeyInfo.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun persist() {
        file.writeAtomically(
            storeJson.encodeToString(ListSerializer(SshKeyInfo.serializer()), _keys.value)
        )
    }

    private fun passphraseKey(id: String) = "passphrase:$id"
}

/** Minimal encoder for the `openssh-key-v1` container holding one unencrypted Ed25519 key. */
internal object OpenSshEd25519 {
    private const val KEY_TYPE = "ssh-ed25519"

    fun publicBlob(publicKey: ByteArray): ByteArray = sshBytes {
        string(KEY_TYPE.toByteArray())
        string(publicKey)
    }

    fun encodePrivateKey(seed: ByteArray, publicKey: ByteArray, comment: String): String {
        val check = SecureRandom().nextInt()
        val privateSection = sshBytes {
            writeInt(check)
            writeInt(check)
            string(KEY_TYPE.toByteArray())
            string(publicKey)
            string(seed + publicKey)
            string(comment.toByteArray())
            var pad = 1
            while (size() % 8 != 0) write(pad++)
        }
        val body = sshBytes {
            write("openssh-key-v1".toByteArray())
            write(0)
            string("none".toByteArray())
            string("none".toByteArray())
            string(ByteArray(0))
            writeInt(1)
            string(publicBlob(publicKey))
            string(privateSection)
        }
        val b64 = Base64.getEncoder().encodeToString(body)
        return buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
    }

    private fun DataOutputStream.string(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private inline fun sshBytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { it.block() }
        return out.toByteArray()
    }
}
