package com.materialagent.notify

import com.materialagent.data.AppSettings
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.data.chat.EntryKind

/**
 * Whether a notification should fire, and where.
 *
 * Pure data: no Android types, so the whole decision tree is unit-testable on
 * the JVM without Robolectric.
 */
data class NotifyPlan(
    /** The channel to post on, or null when nothing should fire. */
    val channel: NotifyChannel?,
    /** Notification title, e.g. the conversation name. */
    val title: String,
    /** Notification body — the answer's first line, or the request's text. */
    val body: String,
) {
    val shouldNotify: Boolean get() = channel != null
}

/**
 * The three notification channels. One enum so the decision, the channel
 * creation and the settings tray all read from the same list and cannot drift.
 */
enum class NotifyChannel(val channelId: String) {
    /** The agent finished a turn while the user was away. */
    TURNS("turns"),

    /**
     * An approval / clarify / sudo / secret request is waiting. These expire
     * fail-closed after roughly a minute, so timeliness is the whole point.
     */
    ATTENTION("attention"),

    /** The socket dropped or came back. Noisy; off by default. */
    CONNECTION("connection"),
}

/**
 * Every state the notifier can be asked about, resolved to a plan.
 *
 * Pure, total function of (event kind, app foregrounded?, settings, what the
 * transcript holds). No Android types, no clocks, no I/O — the same inputs
 * always give the same plan, which is what makes the table in the tests
 * trustworthy.
 */
object NotifyDecision {

    /**
     * A turn finished in the background.
     *
     * A turn that ended in error still notifies: "your agent failed" is exactly
     * what the owner backgrounded the app to find out about. The answer text is
     * deliberately NOT previewed in the body — a transcript can be private
     * (FLAG_SECURE is the default), and the lock screen would show it.
     */
    fun forTurnComplete(
        inForeground: Boolean,
        settings: AppSettings,
        transcript: ChatTranscript,
    ): NotifyPlan {
        if (inForeground) return NotifyPlan(null, "", "")
        if (!settings.notifyTurns) return NotifyPlan(null, "", "")
        return NotifyPlan(
            channel = NotifyChannel.TURNS,
            title = transcript.title.ifBlank { "Agent reply" },
            body = "Your agent finished a turn. Tap to read the reply.",
        )
    }

    /**
     * A blocking interactive request arrived: approval, clarify, sudo or secret.
     *
     * Fires whenever the app is backgrounded and the channel is on. The
     * foreground branch still resolves the plan (so callers can observe the
     * difference) but the notifier must not post it — the screen the user is
     * looking at already carries the card, and a shade entry on top of it is
     * noise. The card is also what expires: ~60 s fail-closed, which is why
     * this channel exists at all.
     */
    fun forInteraction(
        inForeground: Boolean,
        settings: AppSettings,
        transcript: ChatTranscript,
    ): NotifyPlan {
        val pending = transcript.pendingInteractions.lastOrNull()
            ?: return NotifyPlan(null, "", "")
        if (!settings.notifyAttention) return NotifyPlan(null, "", "")
        if (inForeground) return NotifyPlan(null, "", "")
        return NotifyPlan(
            channel = NotifyChannel.ATTENTION,
            title = "The agent needs you",
            body = pending.title.ifBlank { "A request is waiting for your answer" },
        )
    }

    /**
     * The connection dropped or came back while backgrounded. Off by default —
     * reconnect loops would buzz constantly — and the foreground branch is
     * silent for the same reason as interactions.
     */
    fun forConnectionChange(
        inForeground: Boolean,
        settings: AppSettings,
        reconnected: Boolean,
        serverName: String,
    ): NotifyPlan {
        if (inForeground) return NotifyPlan(null, "", "")
        if (!settings.notifyConnection) return NotifyPlan(null, "", "")
        return NotifyPlan(
            channel = NotifyChannel.CONNECTION,
            title = serverName.ifBlank { "Hermes" },
            body = if (reconnected) "Connected again." else "Connection lost. Trying to reconnect…",
        )
    }
}
