package com.materialagent

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.data.AppSettings
import com.materialagent.ui.AgentApp

/**
 * The single activity. Everything above it is Compose; this class exists to install
 * the splash screen, go edge-to-edge, apply the window's capture policy, and hand
 * off to [AgentApp].
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as MaterialAgentApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the splash until the object graph exists, so the first frame the
        // user sees is the real app rather than a blank window.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        ready = true

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // The container holds the store; the store holds the flow.
            val store = container.settings
            // Started from AppSettings() rather than null because its default is
            // the secure one: the preference is read from disk asynchronously, and
            // the frames before it arrives must not be the capturable ones.
            val settings by store.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            ScreenshotPolicy(allowScreenshots = settings.allowScreenshots)
            AgentApp()
        }
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
