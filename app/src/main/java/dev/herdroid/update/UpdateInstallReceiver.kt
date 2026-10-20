package dev.herdroid.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.herdroid.app

/** Outcome of the [PackageInstaller] session started by [PackageUpdateInstaller.commit]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = context.app.updater
        val sessionId = intent.getIntExtra(PackageUpdateInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return
        val pending = goAsync()
        val job = when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) updater.onInstallResult(sessionId, PackageInstaller.STATUS_FAILURE, null)
                else updater.onConfirmationNeeded(sessionId, confirm)
            }
            else -> updater.onInstallResult(sessionId, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
        job.invokeOnCompletion { pending.finish() }
    }
}

/** Android delivers this to the new version after an update; tells the user it went through. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            context.app.updater.onPackageReplaced().invokeOnCompletion { pending.finish() }
        }
    }
}
