package dev.herdroid.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdroid.WoolooMobileApp
import dev.herdroid.MainActivity
import dev.herdroid.R
import dev.herdroid.connection.Notifier

/**
 * Keeps the process alive while SSH connections are open so agent alerts keep arriving
 * with the screen off. It does no work itself; connections live in [WoolooMobileApp].
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            (application as WoolooMobileApp).connections.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(resources.getQuantityString(R.plurals.machine_connections, count, count))
            .setContentText("You'll be notified when an agent needs you")
            .setOngoing(true)
            .setGroup(Notifier.GROUP_CONNECTION)
            .setContentIntent(open)
            .addAction(0, "Disconnect", disconnect)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"
        private const val ACTION_DISCONNECT_ALL = "dev.herdroid.DISCONNECT_ALL"

        fun sync(context: Context, activeConnections: Int) {
            val intent = Intent(context, ConnectionService::class.java)
            if (activeConnections > 0) {
                intent.putExtra(EXTRA_COUNT, activeConnections)
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
