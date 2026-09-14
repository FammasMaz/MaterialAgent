package com.materialagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.materialagent.ui.components.AgentDestination
import com.materialagent.ui.components.AgentNavBar
import com.materialagent.ui.screens.capabilities.CapabilitiesScreen
import com.materialagent.ui.screens.chat.ChatScreen
import com.materialagent.ui.screens.connect.ConnectScreen
import com.materialagent.ui.screens.sessions.SessionsScreen
import com.materialagent.ui.screens.settings.SettingsScreen
import com.materialagent.ui.theme.MaterialAgentTheme

/** Route constants, kept in one place so no screen has to know a string literal. */
object Routes {
    const val SESSIONS = "sessions"
    const val CAPABILITIES = "capabilities"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val NEW_SESSION = "new"

    const val CHAT = "chat"
    const val CHAT_ARG = "storedId"
    const val CHAT_ROUTE = "$CHAT?$CHAT_ARG={$CHAT_ARG}"

    fun chat(storedId: String?) = "$CHAT?$CHAT_ARG=${storedId ?: NEW_SESSION}"
}

private val topLevelRoutes = setOf(Routes.SESSIONS, Routes.CAPABILITIES, Routes.SETTINGS)

/**
 * The whole app: theme, navigation graph and the floating navigation bar.
 *
 * Routing has one rule worth stating — if no server has ever been saved, the app
 * opens on the connect screen rather than an empty inbox. That decision waits for
 * preferences to load instead of guessing from a default value.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentApp() {
    val app = containerViewModel { AgentViewModel(it) }
    val settings by app.settings.collectAsStateWithLifecycle()
    val skin by app.skin.collectAsStateWithLifecycle()
    val loaded by app.profilesLoaded.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    val cue = rememberCue()

    MaterialAgentTheme(
        themeMode = settings.themeMode,
        palette = settings.palette,
        motionLevel = settings.motionLevel,
        hapticLevel = settings.hapticLevel,
        showReasoning = settings.showReasoning,
        showToolCalls = settings.showToolCalls,
        streamingHaptics = settings.streamingHaptics,
        sendOnEnter = settings.sendOnEnter,
        skin = skin,
    ) {
        // One opaque surface under everything: the window is transparent for
        // edge-to-edge, so without this the theme's background never gets painted.
        Surface(color = MaterialTheme.colorScheme.surface) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showNavBar = currentRoute in topLevelRoutes

        LaunchedEffect(loaded, profiles.size, settings.activeProfileId) {
            // No server, no inbox: send a first-time user straight to setup.
            if (loaded && profiles.isEmpty() && currentRoute == Routes.SESSIONS) {
                nav.navigate(Routes.CONNECT) { launchSingleTop = true }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Routes.SESSIONS,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Routes.SESSIONS) {
                    SessionsScreen(
                        onOpenSession = { storedId -> nav.navigate(Routes.chat(storedId)) },
                        onNewConversation = { nav.navigate(Routes.chat(null)) { launchSingleTop = true } },
                        onConnect = { nav.navigate(Routes.CONNECT) },
                        app = app,
                    )
                }

                composable(Routes.CAPABILITIES) {
                    CapabilitiesScreen(
                        app = app,
                        onConnect = { nav.navigate(Routes.CONNECT) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        app = app,
                        onConnect = { nav.navigate(Routes.CONNECT) },
                    )
                }

                composable(Routes.CONNECT) {
                    ConnectScreen(app = app, onDone = { nav.popBackStack() })
                }

                composable(
                    route = Routes.CHAT_ROUTE,
                    arguments = listOf(
                        navArgument(Routes.CHAT_ARG) {
                            type = NavType.StringType
                            defaultValue = Routes.NEW_SESSION
                        },
                    ),
                ) { entry ->
                    val storedId = entry.arguments?.getString(Routes.CHAT_ARG)
                        ?.takeIf { it != Routes.NEW_SESSION }
                    ChatScreen(
                        app = app,
                        storedId = storedId,
                        onBack = { nav.popBackStack() },
                        onSwitchTo = { id -> nav.navigate(Routes.chat(id)) { launchSingleTop = true } },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
            }

            if (showNavBar) {
                AgentNavBar(
                    destinations = listOf(
                        AgentDestination(Routes.SESSIONS, "Sessions", Icons.Rounded.Forum),
                        AgentDestination(Routes.CAPABILITIES, "Agent", Icons.Rounded.AutoAwesome),
                        AgentDestination(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
                    ),
                    currentRoute = currentRoute,
                    onNavigate = { destination ->
                        nav.navigate(destination.route) {
                            popUpTo(Routes.SESSIONS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNewConversation = { nav.navigate(Routes.chat(null)) { launchSingleTop = true } },
                    onCue = cue,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 12.dp),
                )
            }
        }
        }
    }
}
