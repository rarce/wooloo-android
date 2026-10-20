package dev.herdroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HostRepository(private val file: File) {
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()

    fun get(id: String): HostProfile? = _hosts.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(profile: HostProfile) {
        val list = _hosts.value
        val index = list.indexOfFirst { it.id == profile.id }
        val updated = if (index >= 0) profile.copy(
            lastConnectedAt = listOfNotNull(list[index].lastConnectedAt, profile.lastConnectedAt).maxOrNull(),
        ) else profile
        commit(if (index >= 0) list.toMutableList().also { it[index] = updated } else list + updated)
    }

    @Synchronized
    fun delete(id: String) {
        commit(_hosts.value.filterNot { it.id == id })
    }

    /** Keep the profile available for retry if removing its credentials or metadata fails. */
    fun deleteWithCredentials(id: String, secrets: SecretStore) {
        secrets.transaction(emptyMap(), setOf(SecretStore.passwordKey(id), SecretStore.accessSecretKey(id))) {
            delete(id)
        }
    }

    /** The offline demo machine, added the first time it is asked for. */
    @Synchronized
    fun demo(): HostProfile =
        _hosts.value.firstOrNull { it.demo } ?: HostProfile(label = "Demo machine", hostname = "demo", demo = true).also(::upsert)

    @Synchronized
    fun markConnected(id: String) {
        val profile = get(id) ?: return
        upsert(profile.copy(lastConnectedAt = System.currentTimeMillis()))
    }

    private fun load(): List<HostProfile> = file.readStore(emptyList()) {
        storeJson.decodeFromString(ListSerializer(HostProfile.serializer()), it)
    }

    private fun commit(next: List<HostProfile>) {
        file.writeAtomically(storeJson.encodeToString(ListSerializer(HostProfile.serializer()), next))
        _hosts.value = next
    }
}
