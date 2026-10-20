package dev.herdroid.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Checks a downloaded APK and hands it to Android's installer. */
interface UpdateInstaller {
    /** Throws [IOException] unless [apk] is this app at [release]'s version, signed like the installed copy. */
    fun verify(apk: File, release: UpdateRelease)

    /** Stages and fsyncs [apk], returning its session ID without committing. */
    fun prepare(apk: File): Int
    fun commit(sessionId: Int)
    /** IDs of this app's own self-update sessions, including staging interrupted before persistence. */
    fun ownedSessions(): Set<Int>
    fun sessionExists(sessionId: Int): Boolean
    fun abandon(sessionId: Int)
    fun canRequestInstalls(): Boolean
}

class PackageUpdateInstaller(private val context: Context) : UpdateInstaller {

    override fun verify(apk: File, release: UpdateRelease) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, SIGNATURE_FLAGS)
            ?: throw IOException("The download isn't a valid app")
        if (archive.packageName != context.packageName) throw IOException("The download is a different app")
        if (PackageInfoCompat.getLongVersionCode(archive) != release.versionCode) {
            throw IOException("The download isn't version ${release.versionName}")
        }
        // Android refuses an update signed with another key anyway; checking first spares the user a
        // confirmation that can only fail. Some releases don't report an archive's signers: leave it to Android.
        val downloaded = archive.signers() ?: return
        val installed = pm.getPackageInfo(context.packageName, SIGNATURE_FLAGS).signers() ?: return
        if (downloaded.none { it in installed }) throw IOException("The download isn't signed like this app")
    }

    override fun prepare(apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Every update is approved by the user.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
            }
            return sessionId
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    override fun commit(sessionId: Int) {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        context.packageManager.packageInstaller.openSession(sessionId).use { session ->
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    override fun ownedSessions(): Set<Int> = context.packageManager.packageInstaller.mySessions
        .filter { it.appPackageName == context.packageName }
        .map { it.sessionId }
        .toSet()

    override fun sessionExists(sessionId: Int) =
        context.packageManager.packageInstaller.getSessionInfo(sessionId) != null

    override fun abandon(sessionId: Int) = context.packageManager.packageInstaller.abandonSession(sessionId)

    override fun canRequestInstalls() = context.packageManager.canRequestPackageInstalls()

    companion object {
        const val EXTRA_SESSION_ID = "dev.herdroid.update.SESSION_ID"

        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        /** SHA-256 of each signing certificate (including rotated-out ones), or null if unreported. */
        @Suppress("DEPRECATION")
        private fun PackageInfo.signers(): Set<String>? {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                signatures
            }
            if (signatures.isNullOrEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            return signatures.map { signature -> digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
    }
}
