package dev.herdroid.ssh

/**
 * Where herdr's commands run: an authenticated [SshSession], or the offline demo machine. Every
 * remote command line comes from `RemoteCommand` and gets its own [CommandChannel].
 */
interface RemoteShell {
    val isConnected: Boolean
    suspend fun exec(command: String): CommandChannel
    fun close()
}
