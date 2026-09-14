package com.materialagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.materialagent.ui.AgentApp

/**
 * The single activity. Everything above it is Compose; this class exists to install
 * the splash screen, go edge-to-edge, and hand off to [AgentApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the splash until the object graph exists, so the first frame the
        // user sees is the real app rather than a blank window.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        ready = true

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent { AgentApp() }
    }
}
