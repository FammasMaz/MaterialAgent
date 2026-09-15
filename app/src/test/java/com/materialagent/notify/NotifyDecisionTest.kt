package com.materialagent.notify

import com.materialagent.data.AppSettings
import com.materialagent.data.chat.ChatTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification decision table, pinned.
 *
 * [NotifyDecision] is a pure function, so this file is the whole contract: if
 * a change here fails, a user-visible behaviour changed on purpose or by
 * accident — either way it has to be a conscious choice.
 */
class NotifyDecisionTest {

    // ── Turn completion ─────────────────────────────────────────────────────

    @Test
    fun turnCompletionInTheForegroundIsSilent() {
        val plan = NotifyDecision.forTurnComplete(
            inForeground = true,
            settings = AppSettings(),
            transcript = transcript(title = "Deploy"),
        )

        assertFalse(plan.shouldNotify)
        assertNull(plan.channel)
    }

    @Test
    fun turnCompletionInTheBackgroundNotifiesOnTheTurnsChannel() {
        val plan = NotifyDecision.forTurnComplete(
            inForeground = false,
            settings = AppSettings(),
            transcript = transcript(title = "Deploy"),
        )

        assertTrue(plan.shouldNotify)
        assertEquals(NotifyChannel.TURNS, plan.channel)
        assertEquals("Deploy", plan.title)
        assertTrue(plan.body.isNotBlank())
    }

    @Test
    fun aTurnWithNoTitleFallsBackToAGenericOne() {
        val plan = NotifyDecision.forTurnComplete(
            inForeground = false,
            settings = AppSettings(),
            transcript = transcript(title = ""),
        )

        assertTrue(plan.shouldNotify)
        assertEquals("Agent reply", plan.title)
    }

    @Test
    fun turnNotificationsOffMeansSilentEvenInBackground() {
        val plan = NotifyDecision.forTurnComplete(
            inForeground = false,
            settings = AppSettings(notifyTurns = false),
            transcript = transcript(title = "Deploy"),
        )

        assertFalse(plan.shouldNotify)
    }

    // ── Blocking interactions ───────────────────────────────────────────────

    @Test
    fun approvalInTheBackgroundNotifiesOnTheAttentionChannel() {
        val plan = NotifyDecision.forInteraction(
            inForeground = false,
            settings = AppSettings(),
            transcript = transcript(pendingTitle = "rm -rf /tmp/build"),
        )

        assertTrue(plan.shouldNotify)
        assertEquals(NotifyChannel.ATTENTION, plan.channel)
        assertEquals("rm -rf /tmp/build", plan.body)
    }

    @Test
    fun interactionInTheForegroundIsSilent() {
        val plan = NotifyDecision.forInteraction(
            inForeground = true,
            settings = AppSettings(),
            transcript = transcript(pendingTitle = "rm -rf /tmp/build"),
        )

        assertFalse(plan.shouldNotify)
    }

    @Test
    fun attentionOffMeansSilentEvenInBackground() {
        val plan = NotifyDecision.forInteraction(
            inForeground = false,
            settings = AppSettings(notifyAttention = false),
            transcript = transcript(pendingTitle = "rm -rf /tmp/build"),
        )

        assertFalse(plan.shouldNotify)
    }

    @Test
    fun noPendingInteractionMeansSilent() {
        val plan = NotifyDecision.forInteraction(
            inForeground = false,
            settings = AppSettings(),
            transcript = transcript(),
        )

        assertFalse(plan.shouldNotify)
    }

    @Test
    fun anExpiredRequestIsNoLongerPendingAndStaysSilent() {
        // The gateway fails an unanswered approval closed after ~60 s; the
        // transcript's pendingInteractions already filters expired ones. The
        // decision must agree with that view, not re-notify for a dead card.
        val plan = NotifyDecision.forInteraction(
            inForeground = false,
            settings = AppSettings(),
            transcript = ChatTranscript(),
        )

        assertFalse(plan.shouldNotify)
    }

    // ── Connection changes ──────────────────────────────────────────────────

    @Test
    fun connectionChannelIsOffByDefaultSoBackgroundChangesAreSilent() {
        val plan = NotifyDecision.forConnectionChange(
            inForeground = false,
            settings = AppSettings(),
            reconnected = false,
            serverName = "example",
        )

        assertFalse(plan.shouldNotify)
    }

    @Test
    fun connectionOptInNotifiesOnLossAndRecovery() {
        val settings = AppSettings(notifyConnection = true)

        val lost = NotifyDecision.forConnectionChange(false, settings, false, "example")
        val back = NotifyDecision.forConnectionChange(false, settings, true, "example")

        assertTrue(lost.shouldNotify)
        assertEquals(NotifyChannel.CONNECTION, lost.channel)
        assertTrue(back.shouldNotify)
        assertEquals(NotifyChannel.CONNECTION, back.channel)
        assertEquals("example", back.title)
    }

    @Test
    fun connectionChangesInTheForegroundAreSilent() {
        val plan = NotifyDecision.forConnectionChange(
            inForeground = true,
            settings = AppSettings(notifyConnection = true),
            reconnected = false,
            serverName = "example",
        )

        assertFalse(plan.shouldNotify)
    }

    // ── Cross-checks ────────────────────────────────────────────────────────

    @Test
    fun attentionAndTurnsAreIndependentSwitches() {
        val settings = AppSettings(notifyTurns = false, notifyAttention = true)

        val turn = NotifyDecision.forTurnComplete(false, settings, transcript(title = "x"))
        val ask = NotifyDecision.forInteraction(false, settings, transcript(pendingTitle = "y"))

        assertFalse("turns off must not silence attention", turn.shouldNotify)
        assertTrue("attention on must fire on its own", ask.shouldNotify)
    }

    @Test
    fun channelsMapToDistinctChannelIds() {
        val ids = NotifyChannel.entries.map { it.channelId }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun transcript(
        title: String = "",
        pendingTitle: String? = null,
    ): ChatTranscript = ChatTranscript(
        title = title,
        // pendingInteractions is derived from entries' interactive blocks; a
        // real transcript gets here through the reducer. For the decision tests
        // the derived list is the input that matters, and an entry with a
        // non-expired interactive block is exactly what the reducer produces.
        entries = if (pendingTitle == null) emptyList() else listOf(
            com.materialagent.data.chat.TranscriptEntry(
                id = "approval-1",
                kind = com.materialagent.data.chat.EntryKind.APPROVAL,
                interactive = com.materialagent.data.chat.InteractiveRequest(
                    requestId = "req-1",
                    kind = com.materialagent.data.chat.EntryKind.APPROVAL,
                    title = pendingTitle,
                    detail = "",
                ),
            ),
        ),
    )
}
