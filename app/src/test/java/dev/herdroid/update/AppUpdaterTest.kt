package dev.herdroid.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import dev.herdroid.connection.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import android.provider.Settings
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppUpdaterTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("updates-test", Context.MODE_PRIVATE)
    private val server = MockWebServer()

    private val apk = ByteArray(10_000) { it.toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private class FakeInstaller(var rejectWith: String? = null) : UpdateInstaller {
        val installed = mutableListOf<File>()
        override fun verify(apk: File, release: UpdateRelease) { rejectWith?.let { throw IOException(it) } }
        val sessions = mutableSetOf<Int>()
        var nextId = 10
        var allowed = true
        var onCommit: ((Int) -> Unit)? = null
        var onPrepare: (() -> Unit)? = null
        val committed = mutableListOf<Int>()
        val abandoned = mutableListOf<Int>()
        var ownedQueries = 0
        var failAbandon: Int? = null
        override fun prepare(apk: File): Int {
            installed += File(apk.path + ".staged").also { apk.copyTo(it, overwrite = true) }
            return nextId++.also { sessions += it; onPrepare?.invoke() }
        }
        override fun commit(sessionId: Int) { check(sessionId in sessions); committed += sessionId; onCommit?.invoke(sessionId) }
        override fun ownedSessions(): Set<Int> { ownedQueries++; return sessions.toSet() }
        override fun sessionExists(sessionId: Int) = sessionId in sessions
        override fun abandon(sessionId: Int) {
            if (sessionId == failAbandon) throw IOException("Session unavailable")
            abandoned += sessionId
            sessions.remove(sessionId)
        }
        override fun canRequestInstalls() = allowed
    }

    @Before fun setUp() {
        prefs.edit().clear().commit()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun updater(
        installer: UpdateInstaller = FakeInstaller(),
        now: Long = 10 * AppUpdater.CHECK_INTERVAL_MS,
        enabled: Boolean = true,
        preferences: SharedPreferences = prefs,
        versionCode: Long = 1_000,
    ) = AppUpdater(
        context, preferences, Notifier(context),
        UpdateConfig(enabled, server.url("/update.json").toString(), versionCode = versionCode, versionName = "0.1.0"),
        installer = installer,
        uiDispatcher = Dispatchers.Unconfined,
        clock = { now },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun feed(versionCode: Long) = MockResponse().setBody(
        """{"versionCode":$versionCode,"versionName":"0.2.0","url":"https://example.invalid/a.apk",
        |"sha256":"${"b".repeat(64)}","size":10}""".trimMargin()
    )

    private fun release() = UpdateRelease(2_000, "0.2.0", server.url("/a.apk").toString(), apkSha, apk.size.toLong())

    @Test fun automaticCheckOffersANewerRelease(): Unit = runBlocking {
        server.enqueue(feed(2_000))
        val updater = updater()
        updater.check(userInitiated = false)
        assertEquals(2_000L, (updater.state.value as UpdateState.Available).release.versionCode)
        assertEquals(2_000L, updater.prompt.value?.versionCode)
        assertTrue(updater.lastCheckedAt.value > 0)
    }

    @Test fun manualCheckDoesNotPromptAndSameVersionIsUpToDate(): Unit = runBlocking {
        server.enqueue(feed(2_000))
        server.enqueue(feed(1_000))
        val updater = updater()
        updater.check(userInitiated = true)
        assertTrue(updater.state.value is UpdateState.Available)
        assertNull(updater.prompt.value)
        updater.check(userInitiated = true)
        assertEquals(UpdateState.UpToDate, updater.state.value)
    }

    @Test fun unavailableFeedFailsWithoutRecordingSuccessfulCheck(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        val updater = updater()
        updater.check(userInitiated = true)
        assertEquals(UpdateState.Failed("Update check failed (HTTP 404)", null), updater.state.value)
        assertEquals(0L, updater.lastCheckedAt.value)
        updater.check(userInitiated = true)
        assertEquals(UpdateState.Failed("Update check failed (HTTP 503)", null), updater.state.value)
    }

    @Test fun startChecksAtMostOnceADayAndOnlyWhenEnabled(): Unit = runBlocking {
        server.enqueue(feed(1_000))
        val first = updater(now = 10 * AppUpdater.CHECK_INTERVAL_MS)
        first.start()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(5_000) { first.state.first { it == UpdateState.UpToDate } }

        updater(now = 10 * AppUpdater.CHECK_INTERVAL_MS + 1_000).start()
        updater(enabled = false, now = 20 * AppUpdater.CHECK_INTERVAL_MS).start()
        updater(now = 20 * AppUpdater.CHECK_INTERVAL_MS).apply { setAutoCheck(false) }.start()
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test fun installDownloadsVerifiesAndHandsTheApkToAndroid(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val installer = FakeInstaller()
        val updater = updater(installer)
        updater.install(release())
        assertEquals(UpdateState.Installing(release()), updater.state.value)
        assertEquals(apk.toList(), installer.installed.single().readBytes().toList())

        val confirm = Intent("confirm")
        updater.onConfirmationNeeded(10, confirm).join()
        assertEquals(confirm, (updater.state.value as UpdateState.Installing).confirm)
        updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        assertEquals(UpdateState.Failed("Update cancelled", release()), updater.state.value)
    }

    @Test fun recreationAcceptsLateConfirmationAndPersistsCancellation(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val installer = FakeInstaller()
        updater(installer).install(release())
        val restored = updater(installer)
        assertTrue((restored.state.value as UpdateState.Installing).recovered)
        restored.onConfirmationNeeded(10, Intent("confirm")).join()
        assertEquals("confirm", (restored.state.value as UpdateState.Installing).confirm?.action)
        restored.onInstallResult(10, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        assertEquals(UpdateState.Failed("Update cancelled", release()), updater(installer).state.value)
    }

    @Test fun obsoleteCallbacksCannotReplaceNewSession(): Unit = runBlocking {
        val installer = FakeInstaller()
        val updater = updater(installer)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        installer.sessions.remove(10)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_STORAGE, null).join()
        updater.onConfirmationNeeded(10, Intent("old")).join()
        assertEquals(UpdateState.Installing(release()), updater.state.value)
        updater.onConfirmationNeeded(11, Intent("new")).join()
        assertEquals("new", (updater.state.value as UpdateState.Installing).confirm?.action)
    }

    @Test fun recordIsDurableBeforeCommitAndMissingSessionCanRetry(): Unit = runBlocking {
        val installer = FakeInstaller()
        installer.onCommit = { id ->
            val recovered = updater(installer)
            assertEquals(release(), (recovered.state.value as UpdateState.Installing).release)
            assertTrue(id in installer.sessions)
        }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater(installer).install(release())
        installer.sessions.clear()
        val restored = updater(installer)
        restored.onResume()
        withTimeout(5_000) { restored.state.first { it is UpdateState.Failed } }
        assertEquals(release(), (restored.state.value as UpdateState.Failed).release)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        restored.install(release())
        assertEquals(UpdateState.Installing(release()), restored.state.value)
    }

    @Test fun installPermissionSurvivesRecreationAndReturnFromSettings(): Unit = runBlocking {
        val installer = FakeInstaller().apply { allowed = false }
        updater(installer).install(release())
        assertTrue(installer.sessions.isEmpty())
        val restored = updater(installer)
        restored.onResume()
        assertEquals(UpdateState.NeedsInstallPermission(release()), restored.state.value)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        installer.allowed = true
        restored.onResume()
        withTimeout(5_000) { restored.state.first { it is UpdateState.Installing } }
        assertTrue(installer.sessions.isNotEmpty())
    }

    @Test fun staleCallbackFinishesWhileNewDownloadIsPaused(): Unit = runBlocking {
        val installer = FakeInstaller()
        val updater = updater(installer)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)).setBodyDelay(1, TimeUnit.SECONDS))
        val download = async(Dispatchers.IO) { updater.install(release()) }
        withTimeout(5_000) { updater.state.first { it is UpdateState.Downloading } }
        val orphan = File(context.cacheDir, "updates/orphan.apk").apply { writeText("old") }
        updater.setAutoCheck(false)
        updater.start()
        withTimeout(500) { updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_STORAGE, null).join() }
        assertTrue(updater.state.value is UpdateState.Downloading)
        withTimeout(5_000) { download.await() }
        assertTrue(!orphan.exists())
        assertEquals(listOf(10, 11), installer.committed)
    }

    @Test fun fastConfirmationDuringCommitIsNotOverwritten(): Unit = runBlocking {
        val installer = FakeInstaller()
        val updater = updater(installer)
        installer.onCommit = { id -> updater.onConfirmationNeeded(id, Intent("fast")) }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        withTimeout(5_000) { updater.state.first { it is UpdateState.Installing && it.confirm != null } }
        assertEquals("fast", (updater.state.value as UpdateState.Installing).confirm?.action)
    }

    @Test fun recreatedSuccessAndReplacementOnlyClearTheMatchingVersion(): Unit = runBlocking {
        val installer = FakeInstaller()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater(installer).install(release())
        val restored = updater(installer)
        restored.onInstallResult(10, PackageInstaller.STATUS_SUCCESS, null).join()
        assertEquals(UpdateState.Idle, restored.state.value)
        // A delayed replacement for the currently running older version must preserve the pending update.
        restored.onPackageReplaced().join()
        assertTrue(updater(installer).state.value is UpdateState.Installing)
        val updated = updater(installer, versionCode = 2_000)
        updated.onPackageReplaced().join()
        assertEquals(UpdateState.Idle, updated.state.value)
        assertEquals(UpdateState.Idle, updater(installer, versionCode = 2_000).state.value)
    }

    @Test fun permissionActionTargetsThisAppAndDenialStillAllowsRetry(): Unit = runBlocking {
        val installer = FakeInstaller().apply { allowed = false }
        val updater = updater(installer)
        updater.install(release())
        updater.continueInstall()
        val settings = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
        updater.onResume()
        assertEquals(UpdateState.NeedsInstallPermission(release()), updater.state.value)
        updater.continueInstall()
        assertNotNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun recoveredConfirmationCanRestartWithANewSession(): Unit = runBlocking {
        val installer = FakeInstaller()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater(installer).install(release())
        val restored = updater(installer)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        restored.continueInstall()
        withTimeout(5_000) { restored.state.first { it is UpdateState.Installing && !it.recovered } }
        assertTrue(10 !in installer.sessions)
        assertTrue(11 in installer.sessions)
    }

    @Test fun newerInstalledVersionClearsRecoveryWithoutOfferingDowngrade(): Unit = runBlocking {
        val installer = FakeInstaller()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater(installer).install(release())
        val restored = updater(installer, versionCode = 3_000)
        restored.onResume()
        withTimeout(5_000) { restored.state.first { it == UpdateState.Idle } }
        assertEquals(UpdateState.Idle, updater(installer, versionCode = 3_000).state.value)
    }

    private fun preferencesWithFailingWrites(failWrites: () -> Boolean): SharedPreferences {
        val editorInterface = SharedPreferences.Editor::class.java
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") method.invoke(prefs, *(args ?: emptyArray()))
            else {
                val real = prefs.edit()
                lateinit var proxy: Any
                proxy = Proxy.newProxyInstance(editorInterface.classLoader, arrayOf(editorInterface)) { _, edit, values ->
                    if (edit.name == "commit" && failWrites()) false
                    else {
                        val result = edit.invoke(real, *(values ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                }
                proxy
            }
        } as SharedPreferences
    }

    @Test fun confirmationAndCancellationRemainRecoverableWhenPreferenceWritesFail(): Unit = runBlocking {
        var failWrites = false
        val installer = FakeInstaller()
        val updater = updater(installer, preferences = preferencesWithFailingWrites { failWrites })
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        failWrites = true
        updater.onConfirmationNeeded(10, Intent("confirm")).join()
        assertEquals("confirm", (updater.state.value as UpdateState.Installing).confirm?.action)
        updater.onInstallResult(10, PackageInstaller.STATUS_FAILURE_ABORTED, null).join()
        assertEquals(UpdateState.Failed("Update cancelled", release()), updater.state.value)
        failWrites = false
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        assertEquals(listOf(10, 11), installer.committed)
        assertTrue(10 !in installer.sessions)
    }

    @Test fun failedDurableSessionWriteAbandonsWithoutCommittingAndCanRetry(): Unit = runBlocking {
        var failWrites = false
        val wrapped = preferencesWithFailingWrites { failWrites }
        val installer = FakeInstaller().apply { onPrepare = { failWrites = true } }
        val updater = updater(installer, preferences = wrapped)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        assertTrue(installer.sessions.isEmpty())
        assertTrue(installer.committed.isEmpty())
        assertTrue(updater.state.value is UpdateState.Failed)
        failWrites = false
        installer.onPrepare = null
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater.install(release())
        assertEquals(listOf(11), installer.committed)
    }

    @Test fun recreatedUpdaterRetiresSessionLeftByDeathBeforeItsIdWasPersisted(): Unit = runBlocking {
        val installer = FakeInstaller().apply { onPrepare = { throw Error("Simulated process death") } }
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        try {
            updater(installer).install(release())
            throw AssertionError("Expected simulated process death")
        } catch (e: Error) {
            assertEquals("Simulated process death", e.message)
        }
        assertEquals(setOf(10), installer.sessions)
        assertTrue(installer.committed.isEmpty())
        val restored = updater(installer)
        restored.cleanupStaleUpdates()
        assertTrue(installer.sessions.isEmpty())
        assertEquals(listOf(10), installer.abandoned)
        assertTrue(restored.state.value is UpdateState.Failed)
    }

    @Test fun orphanCleanupPreservesPendingCallbackAndContinuesAfterOneAbandonFails(): Unit = runBlocking {
        val installer = FakeInstaller()
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        updater(installer).install(release())
        installer.sessions += setOf(20, 21)
        installer.failAbandon = 20
        val restored = updater(installer)
        restored.cleanupStaleUpdates()
        assertEquals(setOf(10, 20), installer.sessions)
        assertEquals(listOf(21), installer.abandoned)
        restored.onConfirmationNeeded(10, Intent("preserved")).join()
        assertEquals("preserved", (restored.state.value as UpdateState.Installing).confirm?.action)
    }

    @Test fun cleanupDoesNotEnumerateOrAbandonWhilePrepareHasAnUnpersistedSession(): Unit = runBlocking {
        val prepared = CountDownLatch(1)
        val finishPrepare = CountDownLatch(1)
        val installer = FakeInstaller().apply {
            onPrepare = {
                prepared.countDown()
                check(finishPrepare.await(5, TimeUnit.SECONDS))
            }
        }
        val updater = updater(installer)
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val install = async(Dispatchers.IO) { updater.install(release()) }
        try {
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            updater.cleanupStaleUpdates()
            assertEquals(0, installer.ownedQueries)
            assertTrue(installer.abandoned.isEmpty())
            assertEquals(setOf(10), installer.sessions)
        } finally {
            finishPrepare.countDown()
        }
        withTimeout(5_000) { install.await() }
        assertEquals(listOf(10), installer.committed)
        updater.cleanupStaleUpdates()
        assertEquals(1, installer.ownedQueries)
        assertEquals(setOf(10), installer.sessions)
    }

    @Test fun anApkThatFailsVerificationIsNotInstalled(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val installer = FakeInstaller(rejectWith = "The download isn't signed like this app")
        val updater = updater(installer)
        updater.install(release())
        assertEquals(UpdateState.Failed("The download isn't signed like this app", release()), updater.state.value)
        assertTrue(installer.installed.isEmpty())
    }
}
