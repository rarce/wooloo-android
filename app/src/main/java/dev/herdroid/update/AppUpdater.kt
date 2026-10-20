package dev.herdroid.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dev.herdroid.BuildConfig
import dev.herdroid.connection.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

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
    data class Installing(val release: UpdateRelease, val confirm: Intent? = null, val recovered: Boolean = false) : UpdateState
    data class NeedsInstallPermission(val release: UpdateRelease) : UpdateState
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
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val enabled: Boolean get() = config.enabled

    private val downloads = File(context.cacheDir, "updates")

    @Serializable
    private data class Pending(
        val release: UpdateRelease,
        val sessionId: Int = -1,
        val phase: String,
        val message: String? = null,
    )

    private val mutex = Mutex()
    private var activeAttempt: String? = null
    private var pending = prefs.getString(KEY_PENDING, null)?.let {
        runCatching { Json.decodeFromString<Pending>(it) }.getOrNull()
    }
    private val _state = MutableStateFlow<UpdateState>(pending?.toState() ?: UpdateState.Idle)

    private fun Pending.toState(): UpdateState = when (phase) {
        "permission" -> UpdateState.NeedsInstallPermission(release)
        "failed", "missing", "downloading" -> UpdateState.Failed(message ?: "Retry the update", release)
        else -> UpdateState.Installing(release, recovered = true)
    }

    // Called on IO, before committing Android's session. A failed durable write must stop installation.
    private fun save(value: Pending?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(KEY_PENDING) else editor.putString(KEY_PENDING, Json.encodeToString(value))
        if (!editor.commit()) throw IOException("Couldn't save update progress")
        pending = value
    }
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

    /** Restores an interrupted installation before considering an automatic feed check. */
    fun start() {
        if (!enabled) return
        scope.launch { cleanupStaleUpdates() }
        onResume()
        if (pending == null && _autoCheck.value && clock() - _lastCheckedAt.value >= CHECK_INTERVAL_MS) {
            scope.launch { check(userInitiated = false) }
        }
    }

    internal suspend fun cleanupStaleUpdates() = withContext(Dispatchers.IO) {
        mutex.withLock {
            downloads.listFiles()?.filter { it.name != "$activeAttempt.apk" }?.forEach { it.delete() }
            // prepare() creates its session before returning an ID. Never enumerate/abandon while it
            // could be staging; holding this lock also prevents a new attempt starting during cleanup.
            if (activeAttempt != null) return@withLock
            val sessions = runCatching { installer.ownedSessions() }.getOrElse { return@withLock }
            sessions.filter { it != pending?.sessionId }.forEach { sessionId ->
                // Cleanup is best effort; one inaccessible session must not block other orphans.
                runCatching { installer.abandon(sessionId) }
            }
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
        val current = _state.value
        when (current) {
            is UpdateState.NeedsInstallPermission -> launchConfirmation(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            is UpdateState.Installing -> current.confirm?.let(::launchConfirmation) ?: run {
                scope.launch(Dispatchers.IO) {
                    val release = mutex.withLock {
                        val old = pending ?: return@withLock null
                        if (_state.value != current) return@withLock null
                        if (old.sessionId >= 0) runCatching { installer.abandon(old.sessionId) }
                        fail(old.release, "Retry the update")
                        old.release
                    }
                    release?.let { install(it) }
                }
            }
            else -> Unit
        }
    }

    internal suspend fun check(userInitiated: Boolean) {
        val reserved = mutex.withLock {
            if (!enabled || busy || _state.value is UpdateState.NeedsInstallPermission) false
            else { _state.value = UpdateState.Checking; true }
        }
        if (!reserved) return
        _state.value = try {
            val release = http.fetchFeed(config.feedUrl)
            val now = clock()
            _lastCheckedAt.value = now
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (release.versionCode > config.versionCode) {
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

    /** Reconcile on foreground return, including return from this app's install-source settings. */
    fun onResume() {
        if (!enabled) return
        scope.launch(Dispatchers.IO) {
            var resume: UpdateRelease? = null
            mutex.withLock {
                val current = pending ?: return@withLock
                if (activeAttempt != null) return@withLock
                try {
                    if (current.release.versionCode <= config.versionCode) {
                        save(null)
                        prefs.edit().remove(KEY_PENDING_VERSION).commit()
                        _state.value = UpdateState.Idle
                        if (current.release.versionCode == config.versionCode) notifier.appUpdated(config.versionName)
                    } else if (current.phase == "permission") {
                        if (installer.canRequestInstalls()) {
                            _state.value = UpdateState.Available(current.release)
                            resume = current.release
                        }
                    } else if (current.phase != "failed" && !installer.sessionExists(current.sessionId)) {
                        val failed = current.copy(phase = "missing", message = "Installation is no longer available. Retry the update")
                        save(failed)
                        _state.value = failed.toState()
                    }
                } catch (e: Exception) {
                    fail(current.release, e.message ?: "Couldn't recover the installation")
                }
            }
            resume?.let { install(it) }
        }
    }

    internal suspend fun install(release: UpdateRelease) = withContext(Dispatchers.IO) {
        val attempt = UUID.randomUUID().toString()
        val reserved = mutex.withLock {
            if (!enabled || busy || release.versionCode <= config.versionCode) return@withLock false
            try {
                pending?.takeIf { it.phase in setOf("failed", "missing") && it.sessionId >= 0 }?.let {
                    if (installer.sessionExists(it.sessionId)) installer.abandon(it.sessionId)
                }
                if (!installer.canRequestInstalls()) {
                    save(Pending(release, phase = "permission"))
                    _state.value = UpdateState.NeedsInstallPermission(release)
                    return@withLock false
                }
                save(Pending(release, phase = "downloading"))
                activeAttempt = attempt
                _state.value = UpdateState.Downloading(release, 0f)
                true
            } catch (e: Exception) {
                fail(release, e.message ?: "Couldn't prepare the update")
                false
            }
        }
        if (!reserved) return@withContext
        val apk = File(downloads, "$attempt.apk")
        var sessionId = -1
        try {
            // Network and staging must not hold the callback lock: BroadcastReceivers have a deadline.
            http.download(release, apk) { progress ->
                _state.value = UpdateState.Downloading(release, progress)
            }
            installer.verify(apk, release)
            sessionId = installer.prepare(apk)
            mutex.withLock {
                kotlin.check(activeAttempt == attempt)
                save(Pending(release, sessionId, "committed"))
                _state.value = UpdateState.Installing(release)
            }
            installer.commit(sessionId)
            _prompt.value = null
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandon(sessionId) }
            withContext(NonCancellable) {
                mutex.withLock {
                    if (activeAttempt == attempt) fail(
                        release,
                        if (e is CancellationException) "Update interrupted. Retry the update"
                        else e.message ?: "Couldn't install the update",
                    )
                }
            }
            if (e is CancellationException) throw e
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (activeAttempt == attempt) activeAttempt = null }
            }
            // Only this attempt's download; cleanup cannot remove a later installation's APK.
            apk.delete()
        }
    }

    private fun fail(release: UpdateRelease, message: String) {
        val failed = pending?.takeIf { it.release == release }?.copy(phase = "failed", message = message)
            ?: Pending(release, phase = "failed", message = message)
        // If storage itself failed, still release the UI so the user can retry.
        runCatching { save(failed) }
        pending = failed
        _state.value = failed.toState()
    }

    internal fun onConfirmationNeeded(sessionId: Int, confirm: Intent) = scope.launch(Dispatchers.IO) {
        val accepted = mutex.withLock {
            val current = pending?.takeIf { it.sessionId == sessionId && it.phase !in setOf("failed", "success") }
                ?: return@withLock false
            val confirming = current.copy(phase = "confirmation")
            runCatching { save(confirming) }
            pending = confirming
            _state.value = UpdateState.Installing(current.release, confirm)
            true
        }
        if (accepted) withContext(uiDispatcher) { launchConfirmation(confirm) }
    }

    internal fun onInstallResult(sessionId: Int, status: Int, message: String?) = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            val current = pending?.takeIf { it.sessionId == sessionId && it.phase !in setOf("failed", "success") }
                ?: return@withLock
            if (status == PackageInstaller.STATUS_SUCCESS) {
                // Keep the version until MY_PACKAGE_REPLACED can report it, even after process death.
                val success = current.copy(phase = "success")
                runCatching { save(success) }
                pending = success
                _state.value = UpdateState.Idle
            } else {
                val failed = current.copy(phase = "failed", message = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update can't replace the installed app"
                    else -> message?.takeIf { it.isNotBlank() } ?: "Couldn't install the update"
                })
                runCatching { save(failed) }
                pending = failed
                _state.value = failed.toState()
            }
        }
    }

    internal fun onPackageReplaced() = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            val current = pending
            val version = current?.release?.versionCode ?: prefs.getLong(KEY_PENDING_VERSION, 0L)
            // A late replacement broadcast must not erase a newer installation.
            if (version == config.versionCode) {
                save(null)
                prefs.edit().remove(KEY_PENDING_VERSION).commit()
                _state.value = UpdateState.Idle
                notifier.appUpdated(config.versionName)
            }
        }
    }

    private fun launchConfirmation(confirm: Intent) {
        runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_PENDING = "pending_install"
        private const val KEY_PENDING_VERSION = "pending_version"
    }
}
