package dev.herdroid.connection

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.lifecycle.ProcessLifecycleOwner
import dev.herdroid.MainActivity
import dev.herdroid.data.HostProfile
import dev.herdroid.herdr.AgentInfo
import dev.herdroid.herdr.AgentStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NotifierTest {
    @Test fun backgroundAlertsPointToCorrectHostSpaceAndPaneAndUpdateInPlace() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppForeground.onStop(ProcessLifecycleOwner.get())
        val notifier = Notifier(app)
        notifier.createChannels()
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(Notifier.CHANNEL_AGENTS).importance)
        val agent = AgentInfo("pane", workspaceId = "space", tabId = "tab", name = "reviewer",
            title = "Approve command", agentStatus = AgentStatus.BLOCKED)
        val alert = AgentAlert("host", "devbox", agent, "api")
        notifier.agentAlert(alert)
        val notification = manager.activeNotifications.single().notification
        assertEquals("reviewer needs your input", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("devbox · api — Approve command", notification.extras.getString(Notification.EXTRA_TEXT))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("host", intent.getStringExtra(MainActivity.EXTRA_HOST_ID))
        assertEquals("space", intent.getStringExtra(MainActivity.EXTRA_WORKSPACE_ID))
        assertEquals("pane", intent.getStringExtra(MainActivity.EXTRA_PANE_ID))
        notifier.agentAlert(alert.copy(agent = agent.copy(agentStatus = AgentStatus.DONE)))
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("reviewer finished", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
        notifier.promptPending(HostProfile(id = "second", hostname = "example.invalid"))
        notifier.appUpdated("1.2.3")
        assertEquals(3, manager.activeNotifications.size)
        manager.cancelAll()
        AppForeground.onStart(ProcessLifecycleOwner.get())
        try {
            notifier.agentAlert(alert)
            notifier.promptPending(HostProfile(hostname = "example.invalid"))
            assertTrue(manager.activeNotifications.isEmpty())
        } finally { AppForeground.onStop(ProcessLifecycleOwner.get()) }
    }

    @Test fun missingNotificationPermissionSuppressesPosts() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(app).appUpdated("1.2.3")
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
