package com.materialagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.data.AppSettings
import com.materialagent.notify.Notifier
import com.materialagent.ui.AgentApp

/**
 * The single activity. Everything above it is Compose; this class exists to install
 * the splash screen, go edge-to-edge, apply the window's capture policy, drive the
 * background keep-alive at the lifecycle edges, and hand off to [AgentApp].
 *
 * It is also where the notification tap lands: [Notifier.EXTRA_OPEN_SESSION]
 * carries the stored session id, which [AgentApp] picks up as its initial
 * destination so the shade's tap opens the right conversation.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as MaterialAgentApp).container

    /** The stored session id a notification tap asked to open, if any. */
    private var pendingOpenSession: String? = null

    /**
     * The runtime request for POST_NOTIFICATIONS. Asking before API 33 would be
     * pointless (the permission does not exist), and asking unconditionally on
     * every launch would be hostile; the launcher only fires when the user has
     * neither granted nor permanently declined, judged in [maybeAskForNotifications].
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denial is a normal state, not an error: the keep-alive service still
            // runs (it is exempt as a foreground service), but no heads-up or
            // shade entries appear. Nothing to do here — the notifier consults
            // NotificationManager.areNotificationsEnabled() at post time.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the splash until the object graph exists, so the first frame the
        // user sees is the real app rather than a blank window.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        ready = true

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The notifier needs an activity context to post and to build the tap
        // intent. The application context cannot start activities from a
        // notification reliably; this one can.
        container.notifier.context = this
        pendingOpenSession = extractOpenSession(intent)

        // One ask per install, on first use after the permission exists (33+).
        // A permanent denial is respected — Android reports that state and we
        // never re-ask; the settings screen remains the way back.
        maybeAskForNotifications()

        setContent {
            // The container holds the store; the store holds the flow.
            val store = container.settings
            // Started from AppSettings() rather than null because its default is
            // the secure one: the preference is read from disk asynchronously, and
            // the frames before it arrives must not be the capturable ones.
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            ScreenshotPolicy(allowScreenshots = settings.allowScreenshots)
            AgentApp(openSessionOnLaunch = pendingOpenSession)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a tap on a notification while the app is alive lands here
        // rather than in onCreate. The pending id is handed to the running UI on
        // the next composition (see AgentApp's launch effect).
        pendingOpenSession = extractOpenSession(intent)
    }

    private fun extractOpenSession(intent: Intent?): String? =
        intent?.getStringExtra(Notifier.EXTRA_OPEN_SESSION)?.takeIf { it.isNotBlank() }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── Background keep-alive + foreground state ────────────────────────────

    override fun onStart() {
        super.onStart()
        container.onAppForegroundChanged(inForeground = true)
    }

    override fun onStop() {
        // Coming back, the shade cards are stale; the notifier clears them here.
        container.onAppForegroundChanged(inForeground = false)
        super.onStop()
    }

    override fun onDestroy() {
        if (container.notifier.context === this) {
            container.notifier.context = null
        }
        super.onDestroy()
    }
}

/**
 * Puts the window's capture policy on [allowScreenshots].
 *
 * FLAG_SECURE is the mechanism, not a promise in the UI: it is what makes the
 * system refuse a screenshot and what keeps the transcript out of the Recents
 * thumbnail. Cleared rather than never set when the user opts in, so toggling the
 * preference applies to the window that is already on screen.
 */
@Composable
private fun ScreenshotPolicy(allowScreenshots: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        if (allowScreenshots) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
