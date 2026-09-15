package com.materialagent.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.materialagent.MaterialAgentApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the Hermes socket alive while the app is backgrounded.
 *
 * Why this exists at all: the Hermes gateway has no push infrastructure — no
 * FCM, no APNs, nothing server-side that could wake the app. Genuine remote
 * push is therefore impossible from the client alone, and this service does not
 * pretend otherwise. What it can do is keep the existing WebSocket from being
 * frozen with the process, so live events keep arriving while the user is away
 * and [AgentNotifier] can turn them into local notifications.
 *
 * Android freezes a backgrounded process's coroutines (the heartbeat stops, the
 * gateway drops the silent socket — see [com.materialagent.core.HermesClient]'s
 * foreground probe). A foreground service is the one sanctioned way to keep a
 * coroutine running in that state: the OS shows the user a small, silent
 * "Connected to Hermes" card for as long as the socket is being kept alive.
 * It is started when the app leaves the foreground and stopped when it returns,
 * so the card never shows while the app is in use.
 *
 * Everything it observes already exists: the container-wide [ChatController]'s
 * transcript flow and the [com.materialagent.data.HermesConnection] status
 * flow. The service owns no transport of its own.
 */
class NotifyForegroundService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureServiceChannel(this)
        Notifier.ensureChannels(this)
    }

    /**
     * `startForeground` before anything else: the platform gives a service a few
     * seconds to call this after `startForegroundService`, and doing it first is
     * also what makes the ANR impossible.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // Rebuilt on every start so a settings change while the service runs is
        // picked up on the next backgrounding rather than needing a restart.
        val container = (application as com.materialagent.MaterialAgentApp).container
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val notifier = AgentNotifier(container.settings, container.chat, container.connection)
        notifier.context = this
        // The activity's own lifecycle also drives foreground state through the
        // container-level notifier (see MainActivity); this service-level copy
        // starts from the same assumption and is corrected on the next edge.
        notifier.markInForeground(false)
        val serviceScope = scope
        if (serviceScope != null) notifier.observe(serviceScope)

        // START_STICKY: if the system kills us under memory pressure, come back.
        // The observers above are rebuilt per start, so a redelivery cannot
        // double-bind them.
        return START_STICKY
    }

    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        val notification: Notification = Notifier.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 41

        /** Starts the keep-alive, if it is not already running. */
        fun start(context: Context) {
            val intent = Intent(context, NotifyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the keep-alive; the app is back on screen. */
        fun stop(context: Context) {
            context.stopService(Intent(context, NotifyForegroundService::class.java))
        }
    }
}
