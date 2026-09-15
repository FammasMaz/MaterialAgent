package com.materialagent.notify

import android.content.Context
import com.materialagent.data.ChatController
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HermesConnection
import com.materialagent.data.SettingsStore
import com.materialagent.data.chat.ChatTranscript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns live flows into notifications, using [NotifyDecision] for every call.
 *
 * A thin observer, deliberately: it holds no transport and no state beyond the
 * last-seen values needed to detect edges, and every decision goes through the
 * pure function, so the unit tests cover exactly what ships.
 *
 * The stored session id a notification deep-links to comes from the transcript
 * itself — the durable id that survives reconnects and process death, the same
 * one the sessions list navigates by.
 */
class AgentNotifier(
    private val settings: SettingsStore,
    private val chat: ChatController,
    private val connection: HermesConnection,
) {

    /**
     * Is the app on screen right now? Fed by the Activity's lifecycle via
     * [setInForeground]; observers keep running either way, and the decision
     * function silences the foreground branch.
     */
    @Volatile var inForeground: Boolean = true
        private set

    /** Where notifications post from; set by the service, cleared with it. */
    @Volatile var context: Context? = null

    fun markInForeground(value: Boolean) {
        inForeground = value
        // Back on screen, the standing cards are stale: the user is looking at
        // the conversation itself. Clearing also stops the shade filling with
        // one entry per turn over a long session.
        if (value) {
            val context = context ?: return
            NotifyChannel.entries.forEach { Notifier.cancel(context, it) }
        }
    }

    /**
     * Subscribes to the transcript and the connection status for the lifetime
     * of [scope]. Called by the service with its own scope.
     */
    fun observe(scope: CoroutineScope) {
        scope.launch {
            chat.transcript.collect { transcript -> evaluateTranscript(transcript) }
        }
        scope.launch {
            connection.status.collect { status -> evaluateStatus(status) }
        }
    }

    /**
     * The two transcript moments worth a notification: a finished turn (the
     * running edge falling) and a blocking request that was not pending before.
     *
     * Collect, not collectLatest: each evaluation is fast (one settings read,
     * one decision), and events arrive far slower than they can be processed,
     * so dropping intermediate transcripts would only risk missing an edge.
     */
    suspend fun evaluateTranscript(transcript: ChatTranscript) {
        val runningBefore = lastRunning
        lastRunning = transcript.running

        // Turn completion = the running edge falling. A transcript that was
        // never running (a fresh history load) must not fire, hence the edge.
        if (runningBefore && !transcript.running) {
            val plan = NotifyDecision.forTurnComplete(
                inForeground = inForeground,
                settings = settings.snapshot(),
                transcript = transcript,
            )
            post(plan, transcript.storedSessionId)
        }

        val pendingId = transcript.pendingInteractions.lastOrNull()?.requestId
        if (pendingId != null && pendingId != lastPendingId) {
            val plan = NotifyDecision.forInteraction(
                inForeground = inForeground,
                settings = settings.snapshot(),
                transcript = transcript,
            )
            post(plan, transcript.storedSessionId)
        }
        lastPendingId = pendingId
    }

    /**
     * Connection edges only: a steady Reconnecting state must not re-buzz on
     * every backoff tick. The channel is off by default anyway.
     */
    suspend fun evaluateStatus(status: ConnectionStatus) {
        val wasConnected = lastConnected
        val isConnected = status.isConnected
        lastConnected = isConnected
        if (wasConnected == null || wasConnected == isConnected) return // no edge

        val plan = NotifyDecision.forConnectionChange(
            inForeground = inForeground,
            settings = settings.snapshot(),
            reconnected = isConnected,
            serverName = (status as? ConnectionStatus.Connected)?.profile?.name
                ?: lastProfileName.orEmpty(),
        )
        if (isConnected) lastProfileName = (status as? ConnectionStatus.Connected)?.profile?.name
        post(plan, null)
    }

    private fun post(plan: NotifyPlan, storedSessionId: String?) {
        if (!plan.shouldNotify) return
        val context = context ?: return
        Notifier.post(context, plan, storedSessionId)
    }

    // Last-seen values for edge detection. Plain fields, not StateFlows: they
    // are only read inside the collectors, which are serial per flow.
    private var lastRunning: Boolean = false
    private var lastPendingId: String? = null
    private var lastConnected: Boolean? = null
    private var lastProfileName: String? = null
}
