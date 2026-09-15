package com.materialagent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.materialagent.MainActivity
import com.materialagent.R

/**
 * Creates the channels and posts the notifications the [NotifyDecision] plans.
 *
 * All the policy lives in [NotifyDecision]; this class only knows how to turn a
 * plan into system calls, and never decides anything itself.
 */
object Notifier {

    /** Extra on the tap intent: which conversation to open, by stored id. */
    const val EXTRA_OPEN_SESSION = "com.materialagent.open_session"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(
                NotifyChannel.TURNS.channelId,
                context.getString(R.string.channel_turns_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_turns_desc)
            },
            NotificationChannel(
                NotifyChannel.ATTENTION.channelId,
                context.getString(R.string.channel_attention_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_attention_desc)
            },
            NotificationChannel(
                NotifyChannel.CONNECTION.channelId,
                context.getString(R.string.channel_connection_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_connection_desc)
            },
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    /**
     * Posts a planned notification. Returns false when the plan says silent or
     * the OS refuses — a denial is a normal state on API 33+, never a crash.
     */
    fun post(context: Context, plan: NotifyPlan, storedSessionId: String?): Boolean {
        val channel = plan.channel ?: return false
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !manager.areNotificationsEnabled()
        ) {
            return false
        }

        val builder = NotificationCompat.Builder(context, channel.channelId)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(plan.title)
            .setContentText(plan.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.body))
            .setAutoCancel(true)
        if (storedSessionId != null) {
            builder.setContentIntent(tapIntent(context, storedSessionId))
        }
        // Every plan has its own id so a turn completion and a waiting request
        // never overwrite each other in the shade.
        return runCatching {
            manager.notify(notificationId(channel), builder.build())
            true
        }.getOrDefault(false)
    }

    /** Cancels one channel's standing notification, e.g. on reconnect. */
    fun cancel(context: Context, channel: NotifyChannel) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(channel))
    }

    private fun notificationId(channel: NotifyChannel): Int = channel.name.hashCode()

    /**
     * The tap action: straight into the conversation the notification is about,
     * by stored session id — the durable one, which survives a process death
     * between posting and tapping. `singleTask` means this routes through
     * [MainActivity.onNewIntent] when the app is already alive.
     */
    private fun tapIntent(context: Context, storedSessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_SESSION, storedSessionId)
            // New task: the notification fires from a non-activity context.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            storedSessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The foreground service's quiet "still listening" card. */
    fun serviceNotification(context: Context): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    /**
     * A dedicated channel for the service's own card: MINIMIZE importance and
     * silence, so "Connected to Hermes" never buzzes or dings.
     */
    const val SERVICE_CHANNEL_ID = "service"

    fun ensureServiceChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.channel_service_desc)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
