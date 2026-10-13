package dev.herdroid.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.herdroid.connection.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        override fun install(apk: File) { installed += apk }
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
    ) = AppUpdater(
        context, prefs, Notifier(context),
        UpdateConfig(enabled, server.url("/update.json").toString(), versionCode = 1_000, versionName = "0.1.0"),
        installer = installer,
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

    @Test fun noPublishedReleaseIsUpToDateAndErrorsAreReported(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        val updater = updater()
        updater.check(userInitiated = true)
        assertEquals(UpdateState.UpToDate, updater.state.value)
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
        updater.onConfirmationNeeded(confirm)
        assertEquals(confirm, (updater.state.value as UpdateState.Installing).confirm)
        updater.onInstallResult(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(UpdateState.Failed("Update cancelled", release()), updater.state.value)
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
