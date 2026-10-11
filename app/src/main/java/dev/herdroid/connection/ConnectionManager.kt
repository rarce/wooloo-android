package dev.herdroid.connection

import android.content.Context
import dev.herdroid.data.HostRepository
import dev.herdroid.data.HostProfile
import dev.herdroid.data.KnownHostsStore
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyManager
import dev.herdroid.service.ConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConnectionDeps(
    val hosts: HostRepository,
    val keys: SshKeyManager,
    val secrets: SecretStore,
    val knownHosts: KnownHostsStore,
    val notifier: Notifier,
    val canReadQuotas: (HostProfile) -> Boolean = { false },
)

/** Owns every live [HerdrConnection]; several machines can be connected at once. */
class ConnectionManager(
    private val context: Context,
    private val deps: ConnectionDeps,
) {
    private val _connections = MutableStateFlow<Map<String, HerdrConnection>>(emptyMap())
    val connections: StateFlow<Map<String, HerdrConnection>> = _connections.asStateFlow()

    fun get(hostId: String): HerdrConnection? = _connections.value[hostId]

    /** Returns the live connection for a host, starting it if needed. */
    fun connect(hostId: String): HerdrConnection? {
        get(hostId)?.let { existing ->
            if (existing.status.value is ConnectionStatus.Failed) existing.retryNow()
            return existing
        }
        val profile = deps.hosts.get(hostId) ?: return null
        val connection = HerdrConnection(profile, deps)
        _connections.update { it + (hostId to connection) }
        connection.start()
        ConnectionService.sync(context, _connections.value.size)
        return connection
    }

    fun disconnect(hostId: String) {
        val connection = get(hostId) ?: return
        _connections.update { it - hostId }
        connection.shutdown()
        ConnectionService.sync(context, _connections.value.size)
    }

    fun disconnectAll() {
        val all = _connections.value
        _connections.value = emptyMap()
        all.values.forEach { it.shutdown() }
        ConnectionService.sync(context, 0)
    }

    /** Called after a host profile is edited, so new settings apply on the next reconnect. */
    fun profileChanged(hostId: String) {
        val profile = deps.hosts.get(hostId) ?: return
        get(hostId)?.updateProfile(profile)
    }

    /** Network came back: skip any pending backoff. */
    fun onNetworkAvailable() {
        _connections.value.values
            .filter { it.status.value is ConnectionStatus.Reconnecting }
            .forEach { it.retryNow() }
    }
}
