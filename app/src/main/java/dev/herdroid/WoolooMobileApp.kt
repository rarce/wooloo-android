package dev.herdroid

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.herdroid.connection.AppForeground
import dev.herdroid.connection.ConnectionDeps
import dev.herdroid.connection.ConnectionManager
import dev.herdroid.connection.Notifier
import dev.herdroid.data.HostRepository
import dev.herdroid.data.KnownHostsStore
import dev.herdroid.data.SecretStore
import dev.herdroid.data.SshKeyManager
import dev.herdroid.data.UiPreferences
import dev.herdroid.data.quotaConsentKey
import dev.herdroid.update.AppUpdater
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

class WoolooMobileApp : Application() {
    lateinit var hosts: HostRepository; private set
    lateinit var keys: SshKeyManager; private set
    lateinit var secrets: SecretStore; private set
    lateinit var knownHosts: KnownHostsStore; private set
    lateinit var notifier: Notifier; private set
    lateinit var connections: ConnectionManager; private set
    lateinit var preferences: UiPreferences; private set
    lateinit var updater: AppUpdater; private set

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
        val dir = File(filesDir, "store")
        secrets = SecretStore(this)
        hosts = HostRepository(File(dir, "hosts.json"))
        keys = SshKeyManager(File(dir, "keys.json"), secrets)
        knownHosts = KnownHostsStore(File(dir, "known_hosts.json"))
        notifier = Notifier(this).also { it.createChannels() }
        preferences = UiPreferences(getSharedPreferences("ui", Context.MODE_PRIVATE))
        connections = ConnectionManager(
            this,
            ConnectionDeps(hosts, keys, secrets, knownHosts, notifier,
                canReadQuotas = { quotaConsentKey(it) in preferences.agentQuotaContexts.value }),
        )
        updater = AppUpdater(this, getSharedPreferences("updates", Context.MODE_PRIVATE), notifier)
        AppForeground.install()
        updater.start()
        watchNetwork()
    }

    /** Android ships a stripped "BC" provider; sshj needs the full one for Ed25519/X25519. */
    private fun installBouncyCastle() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connections.onNetworkAvailable()
                }
            })
        }
    }
}

val Context.app: WoolooMobileApp get() = applicationContext as WoolooMobileApp
