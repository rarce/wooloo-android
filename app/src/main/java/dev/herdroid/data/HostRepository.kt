package dev.herdroid.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HostRepository(private val file: File) {
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()

    fun get(id: String): HostProfile? = _hosts.value.firstOrNull { it.id == id }

    fun upsert(profile: HostProfile) {
        _hosts.update { list ->
            val index = list.indexOfFirst { it.id == profile.id }
            if (index >= 0) list.toMutableList().also { it[index] = profile } else list + profile
        }
        persist()
    }

    fun delete(id: String) {
        _hosts.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun markConnected(id: String) {
        val profile = get(id) ?: return
        upsert(profile.copy(lastConnectedAt = System.currentTimeMillis()))
    }

    private fun load(): List<HostProfile> = runCatching {
        if (!file.exists()) emptyList()
        else storeJson.decodeFromString(ListSerializer(HostProfile.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun persist() {
        file.writeAtomically(
            storeJson.encodeToString(ListSerializer(HostProfile.serializer()), _hosts.value)
        )
    }
}
