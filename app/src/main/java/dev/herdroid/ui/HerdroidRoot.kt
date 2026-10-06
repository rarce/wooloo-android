package dev.herdroid.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.herdroid.OpenRequest
import dev.herdroid.app
import dev.herdroid.ui.hosts.HostEditScreen
import dev.herdroid.ui.hosts.HostsScreen
import dev.herdroid.ui.hosts.KeysScreen
import dev.herdroid.ui.session.SessionScreen
import dev.herdroid.ui.terminal.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URLDecoder
import java.net.URLEncoder

private object Routes {
    const val HOSTS = "hosts"
    const val EDIT = "edit?hostId={hostId}"
    const val KEYS = "keys?import={import}"
    const val SETTINGS = "settings"
    const val SESSION = "session/{hostId}"
    const val TERMINAL = "terminal/{hostId}/{paneId}"

    fun edit(hostId: String?) = if (hostId == null) "edit" else "edit?hostId=$hostId"
    fun keys(import: Boolean = false) = "keys?import=$import"
    fun session(hostId: String) = "session/$hostId"
    fun terminal(hostId: String, paneId: String) =
        "terminal/$hostId/${URLEncoder.encode(paneId, "UTF-8")}"
}

@Composable
fun HerdroidRoot(openRequests: MutableStateFlow<OpenRequest?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val request by openRequests.collectAsState()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Notification tap: jump straight to the agent's pane.
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        openRequests.value = null
        if (context.app.hosts.get(r.hostId) == null) return@LaunchedEffect
        nav.navigate(Routes.session(r.hostId)) { launchSingleTop = true }
        r.paneId?.let { nav.navigate(Routes.terminal(r.hostId, it)) }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOSTS,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable(Routes.HOSTS) {
            HostsScreen(
                onOpenHost = { nav.navigate(Routes.session(it)) },
                onAddHost = { nav.navigate(Routes.edit(null)) },
                onEditHost = { nav.navigate(Routes.edit(it)) },
                onKeys = { nav.navigate(Routes.keys()) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("hostId") { type = NavType.StringType; nullable = true }),
        ) { entry ->
            HostEditScreen(
                hostId = entry.arguments?.getString("hostId"),
                onDone = { savedId, connect ->
                    nav.popBackStack()
                    if (connect && savedId != null) {
                        context.app.connections.get(savedId)?.retryNow()
                        nav.navigate(Routes.session(savedId)) { launchSingleTop = true }
                    }
                },
                onImportKey = { nav.navigate(Routes.keys(import = true)) },
            )
        }
        composable(
            Routes.KEYS,
            arguments = listOf(navArgument("import") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            KeysScreen(onBack = { nav.popBackStack() }, startWithImport = entry.arguments?.getBoolean("import") == true)
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SESSION) { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            SessionScreen(
                hostId = hostId,
                onBack = { nav.popBackStack() },
                onOpenPane = { nav.navigate(Routes.terminal(hostId, it)) },
                onEditHost = { nav.navigate(Routes.edit(hostId)) },
            )
        }
        composable(Routes.TERMINAL) { entry ->
            val hostId = entry.arguments?.getString("hostId").orEmpty()
            val paneId = URLDecoder.decode(entry.arguments?.getString("paneId").orEmpty(), "UTF-8")
            TerminalScreen(
                hostId = hostId,
                initialPaneId = paneId,
                onBack = { nav.popBackStack() },
                onEditHost = { nav.navigate(Routes.edit(hostId)) },
            )
        }
    }
}
