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

    /** Commits [apk] to a [PackageInstaller] session; [UpdateInstallReceiver] gets the outcome. */
    fun install(apk: File)
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

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Once this app installed itself, Android 12+ updates it without asking again.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // The installer fills in the status extras, so the intent must be mutable (and explicit).
                val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        val SIGNATURE_FLAGS =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        /** SHA-256 of each signing certificate (including rotated-out ones), or null if unreported. */
        @Suppress("DEPRECATION")
        fun PackageInfo.signers(): Set<String>? {
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
