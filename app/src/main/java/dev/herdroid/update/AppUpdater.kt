package dev.herdroid.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import dev.herdroid.BuildConfig
import dev.herdroid.connection.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What this build is and where its updates come from; only published release builds update themselves. */
data class UpdateConfig(
    val enabled: Boolean = BuildConfig.UPDATES,
    val feedUrl: String = BuildConfig.UPDATE_FEED_URL,
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    val versionName: String = BuildConfig.VERSION_NAME,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateState
    /** Handed to Android; [confirm] is set while Android waits for the user to approve it. */
    data class Installing(val release: UpdateRelease, val confirm: Intent? = null) : UpdateState
    data class Failed(val message: String, val release: UpdateRelease?) : UpdateState
}

/**
 * Updates the app from its GitHub releases, like wooloo does with Sparkle: [UpdateConfig.feedUrl]
 * points at `update.json` on the latest release, checked at most once a day while automatic checks
 * are on, and the APK is downloaded, verified and installed through [PackageInstaller].
 */
class AppUpdater(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val notifier: Notifier,
    private val config: UpdateConfig = UpdateConfig(),
    private val http: UpdateHttp = UpdateHttp(),
    private val installer: UpdateInstaller = PackageUpdateInstaller(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val enabled: Boolean get() = config.enabled

    private val downloads = File(context.cacheDir, "updates")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** A release found by an automatic check, offered once in a dialog until dismissed. */
    private val _prompt = MutableStateFlow<UpdateRelease?>(null)
    val prompt: StateFlow<UpdateRelease?> = _prompt.asStateFlow()

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    /** Epoch millis of the last successful check, 0 if never. */
    private val _lastCheckedAt = MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L))
    val lastCheckedAt: StateFlow<Long> = _lastCheckedAt.asStateFlow()

    private val busy: Boolean
        get() = _state.value.let {
            it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing
        }

    /** Called once at launch: drops a previous download and checks if a day has passed. */
    fun start() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) { downloads.deleteRecursively() }
        if (_autoCheck.value && clock() - _lastCheckedAt.value >= CHECK_INTERVAL_MS) {
            scope.launch { check(userInitiated = false) }
        }
    }

    fun setAutoCheck(on: Boolean) {
        _autoCheck.value = on
        prefs.edit().putBoolean(KEY_AUTO_CHECK, on).apply()
    }

    fun checkNow() {
        scope.launch { check(userInitiated = true) }
    }

    fun startInstall(release: UpdateRelease) {
        scope.launch { install(release) }
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    /** Reopens Android's confirmation if it couldn't come up on its own (the app was in the background). */
    fun continueInstall() {
        (_state.value as? UpdateState.Installing)?.confirm?.let(::launchConfirmation)
    }

    internal suspend fun check(userInitiated: Boolean) {
        if (!enabled || busy) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val release = http.fetchFeed(config.feedUrl)
            val now = clock()
            _lastCheckedAt.value = now
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (release != null && release.versionCode > config.versionCode) {
                if (!userInitiated) _prompt.value = release
                UpdateState.Available(release)
            } else {
                UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "Couldn't check for updates", null)
        }
    }

    internal suspend fun install(release: UpdateRelease) {
        if (!enabled || busy) return
        val apk = File(downloads, "update.apk")
        try {
            _state.value = UpdateState.Downloading(release, 0f)
            var shown = 0
            http.download(release, apk) { progress ->
                // Progress arrives per 64 KiB chunk; whole percents are plenty for the UI.
                val percent = (progress * 100).toInt()
                if (percent != shown) {
                    shown = percent
                    _state.value = UpdateState.Downloading(release, progress)
                }
            }
            withContext(Dispatchers.IO) { installer.verify(apk, release) }
            _state.value = UpdateState.Installing(release)
            prefs.edit().putLong(KEY_PENDING_VERSION, release.versionCode).apply()
            withContext(Dispatchers.IO) { installer.install(apk) }
            _prompt.value = null
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            prefs.edit().remove(KEY_PENDING_VERSION).apply()
            _state.value = UpdateState.Failed(e.message ?: "Couldn't install the update", release)
        }
    }

    internal fun onConfirmationNeeded(confirm: Intent) {
        val installing = _state.value as? UpdateState.Installing ?: return
        _state.value = installing.copy(confirm = confirm)
        launchConfirmation(confirm)
    }

    internal fun onInstallResult(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Android normally stops this process first; the new version reports through onPackageReplaced.
            _state.value = UpdateState.Idle
            return
        }
        prefs.edit().remove(KEY_PENDING_VERSION).apply()
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = UpdateState.Failed(
            when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "This update can't replace the installed app"
                else -> message?.takeIf { it.isNotBlank() } ?: "Couldn't install the update"
            },
            release,
        )
    }

    /** Runs in the new version: confirm an update this updater started, and drop its download. */
    internal fun onPackageReplaced() {
        val pending = prefs.getLong(KEY_PENDING_VERSION, 0L)
        prefs.edit().remove(KEY_PENDING_VERSION).apply()
        scope.launch(Dispatchers.IO) { downloads.deleteRecursively() }
        if (pending != 0L && pending == config.versionCode) notifier.appUpdated(config.versionName)
    }

    private fun launchConfirmation(confirm: Intent) {
        runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_PENDING_VERSION = "pending_version"
    }
}
