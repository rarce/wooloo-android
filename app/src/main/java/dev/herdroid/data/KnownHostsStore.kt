package dev.herdroid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
)

/** Trust-on-first-use host key store, keyed by host and port. */
class KnownHostsStore(private val file: File) {
    @Volatile
    private var entries: List<KnownHost> = load()

    fun find(host: String, port: Int): KnownHost? =
        entries.firstOrNull { it.host.equals(host, ignoreCase = true) && it.port == port }

    @Synchronized
    fun trust(entry: KnownHost) {
        val next = entries.filterNot {
            it.host.equals(entry.host, ignoreCase = true) && it.port == entry.port
        } + entry
        file.writeAtomically(storeJson.encodeToString(ListSerializer(KnownHost.serializer()), next))
        entries = next
    }

    @Synchronized
    fun forget(host: String, port: Int) {
        val next = entries.filterNot { it.host.equals(host, ignoreCase = true) && it.port == port }
        file.writeAtomically(storeJson.encodeToString(ListSerializer(KnownHost.serializer()), next))
        entries = next
    }

    private fun load(): List<KnownHost> = file.readStore(emptyList()) {
        storeJson.decodeFromString(ListSerializer(KnownHost.serializer()), it)
    }
}
