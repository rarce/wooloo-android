package dev.herdroid.connection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.herdroid.MainActivity
import dev.herdroid.R
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.AgentStatus

/** Posts agent-attention notifications while the app is in the background. */
class Notifier(private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_AGENTS, "Agents needing you", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "An agent is blocked on input or just finished its work" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Shows while Herdroid keeps SSH connections open"
                    setShowBadge(false)
                }
        )
    }

    fun agentAlert(alert: AgentAlert) {
        if (AppForeground.isForeground) return
        val agent = alert.agent
        val title = when (agent.agentStatus) {
            AgentStatus.BLOCKED -> "${agent.displayName} needs your input"
            else -> "${agent.displayName} finished"
        }
        val where = listOfNotNull(alert.hostName, alert.workspaceLabel?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        val body = listOfNotNull(where, agent.subtitle).joinToString(" — ")
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(GROUP_AGENTS)
            .setContentIntent(openPaneIntent(alert.hostId, agent.paneId, agent.workspaceId))
            .build()
        post((alert.hostId + agent.paneId).hashCode(), notification)
    }

    fun promptPending(profile: HostProfile) {
        if (AppForeground.isForeground) return
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${profile.displayName} needs confirmation")
            .setContentText("Open Herdroid to finish connecting")
            .setAutoCancel(true)
            .setGroup(GROUP_AGENTS)
            .setContentIntent(openPaneIntent(profile.id, null))
            .build()
        post(profile.id.hashCode(), notification)
    }

    private fun post(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openPaneIntent(hostId: String, paneId: String?, workspaceId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_HOST_ID, hostId)
            paneId?.let { putExtra(MainActivity.EXTRA_PANE_ID, it) }
            workspaceId?.let { putExtra(MainActivity.EXTRA_WORKSPACE_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            (hostId + paneId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_AGENTS = "agents"
        const val CHANNEL_CONNECTION = "connection"

        /*
         * Explicit groups keep Android from auto-bundling alerts with the ongoing connection
         * notification: that bundle inherits "ongoing", so neither swipe nor "Clear all" removes them.
         */
        const val GROUP_AGENTS = "agents"
        const val GROUP_CONNECTION = "connection"
    }
}
