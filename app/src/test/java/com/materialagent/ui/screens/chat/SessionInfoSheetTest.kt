package com.materialagent.ui.screens.chat

import com.materialagent.core.model.SessionInfo
import com.materialagent.core.model.SessionSummary
import com.materialagent.core.model.Usage
import com.materialagent.data.chat.ChatTranscript
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation-info surface, tested where it can be.
 *
 * The maths and the field mapping are the parts that can be wrong without looking
 * wrong: a bar that reads 0% because the window was missing, or a "copy all" that
 * quietly drops the id the user opened the sheet for. Both are pure functions, so
 * both are checked here rather than by squinting at an emulator.
 */
class SessionInfoSheetTest {

    private fun usage(
        input: Long = 0,
        output: Long = 0,
        reasoning: Long = 0,
        total: Long = 0,
        contextUsed: Long = 0,
        contextMax: Long = 0,
        calls: Int = 0,
        percent: Int = 0,
    ) = Usage(
        model = "gpt-5",
        input = input,
        output = output,
        reasoning = reasoning,
        total = total,
        calls = calls,
        contextUsed = contextUsed,
        contextMax = contextMax,
        contextPercent = percent,
        cacheHitPercent = null,
        avgTps = null,
        avgLatencyS = null,
        compressions = 0,
        activeSubagents = 0,
    )

    private fun info(
        model: String? = null,
        project: String? = null,
        terminalBackend: String? = null,
        storedSessionId: String? = null,
        fast: Boolean = false,
        yolo: Boolean = false,
        usage: Usage? = null,
    ) = SessionInfo(
        model = model,
        provider = null,
        reasoningEffort = null,
        serviceTier = null,
        fast = fast,
        yolo = yolo,
        approvalMode = null,
        cwd = null,
        branch = null,
        project = project,
        terminalBackend = terminalBackend,
        title = null,
        storedSessionId = storedSessionId,
        profileName = null,
        personality = null,
        running = false,
        turnStartedAt = null,
        version = null,
        usage = usage,
        tools = emptyMap(),
        skills = emptyMap(),
        mcpServers = null,
    )

    @Test
    fun tokensReadTheWayAPersonCountsThem() {
        assertEquals("0", formatTokens(0))
        assertEquals("845", formatTokens(845))
        assertEquals("999", formatTokens(999))
        assertEquals("1k", formatTokens(1_000))
        assertEquals("12.3k", formatTokens(12_300))
        assertEquals("1.2M", formatTokens(1_234_567))
    }

    @Test
    fun noWindowReportedIsNotAWindowOfZero() {
        // A session between turns has a null usage block, and a zero window is what
        // an older server sends. Neither is "0% of 0" and neither should draw a bar.
        assertNull(contextWindow(null))
        assertNull(contextWindow(usage(contextUsed = 500, contextMax = 0)))
    }

    @Test
    fun aReportedWindowGivesUsedAndPercent() {
        val window = contextWindow(usage(contextUsed = 50_000, contextMax = 200_000))!!
        assertEquals(50_000L, window.used)
        assertEquals(200_000L, window.max)
        assertEquals(25, window.percent)
        assertEquals(0.25f, window.fraction, 0.0001f)
    }

    @Test
    fun overfullContextClampsInsteadOfOverflowingTheBar() {
        // The server can report used > max mid-compression. An unclamped fraction
        // would push the indicator past its track and read as more than full.
        val window = contextWindow(usage(contextUsed = 260_000, contextMax = 200_000))!!
        assertEquals(1f, window.fraction, 0.0001f)
        assertEquals(100, window.percent)
    }

    @Test
    fun anEmptyUsageBlockIsNotWorthAHeading() {
        // Every figure the breakdown would draw is zero and no rate was sent, so
        // there is nothing to show and nothing to head.
        assertTrue(!usage().hasReportedNumbers())
        assertTrue(!usage(contextUsed = 500, contextMax = 200_000).hasReportedNumbers())
        assertTrue(usage(input = 1).hasReportedNumbers())
        assertTrue(usage(total = 12_300).hasReportedNumbers())
        assertTrue(usage(calls = 1).hasReportedNumbers())
    }

    @Test
    fun aMissingFastTierIsNotAnOffFastTier() {
        // "Off" is a claim about a session that never mentioned one, and this app
        // does not get to make it up.
        val unreported = SessionFacts.from(
            ChatTranscript(sessionId = "ab12cd34"),
            summary = null,
        )
        assertNull(unreported.fast)
        assertNull(unreported.toolCount)
        assertNull(unreported.skillCount)

        val off = SessionFacts.from(
            ChatTranscript(sessionId = "ab12cd34", info = info(fast = false)),
            summary = null,
        )
        assertEquals(false, off.fast)
        // An empty catalogue the server did send is a real zero, not a gap.
        assertEquals(0, off.toolCount)
    }

    @Test
    fun aHeadingGoesWhenItsWholeGroupIsMissing() {
        // A brand new conversation has no ids at all — the session does not exist
        // until the first turn — so the "Session" heading must not stand over an
        // empty group.
        val nothing = SessionFacts.from(ChatTranscript(), summary = null)
        assertTrue(!nothing.hasSessionRows())

        val started = SessionFacts.from(
            ChatTranscript(sessionId = "ab12cd34"),
            summary = null,
        )
        assertTrue(started.hasSessionRows())
    }

    @Test
    fun factsTakeTheIdsFromTheTranscriptAndTheRestFromTheSummary() {
        val transcript = ChatTranscript(
            sessionId = "ab12cd34",
            storedSessionId = "20260914_152925_626312",
            title = "Fix the parser",
            info = info(model = "codex/gpt-5.6-luna", project = "materialagent"),
        )
        val summary = SessionSummary(
            id = "20260914_152925_626312",
            title = "Fix the parser",
            preview = "",
            startedAt = 1_789_392_566.9,
            messageCount = 8,
            source = "tui",
        )

        val facts = SessionFacts.from(transcript, summary)
        assertEquals("ab12cd34", facts.runtimeId)
        assertEquals("20260914_152925_626312", facts.storedId)
        assertEquals("codex/gpt-5.6-luna", facts.model)
        assertEquals("materialagent", facts.project)
        assertEquals("tui", facts.source)
        assertEquals(8, facts.messageCount)
        assertEquals(1_789_392_566.9, facts.startedAt!!, 0.001)
        // The label the inbox would give the same row, so the two screens agree.
        assertEquals("Terminal", summary.groupLabel)
    }

    @Test
    fun anUnstoredSessionSimplyHasNoStoredFacts() {
        // Hermes does not persist a session until its first turn completes, so every
        // stored-only field is genuinely absent rather than unknown.
        val facts = SessionFacts.from(
            ChatTranscript(sessionId = "ab12cd34", title = "New conversation"),
            summary = null,
        )
        assertNull(facts.storedId)
        assertNull(facts.source)
        assertNull(facts.messageCount)
        assertNull(facts.startedAt)
        assertEquals("ab12cd34", facts.runtimeId)
    }

    @Test
    fun theReportCarriesEveryIdAndSkipsWhatTheServerNeverSent() {
        val facts = SessionFacts.from(
            ChatTranscript(
                sessionId = "ab12cd34",
                storedSessionId = "20260914_152925_626312",
                title = "Fix the parser",
                info = info(model = "codex/gpt-5.6-luna", project = null, fast = true),
            ),
            summary = SessionSummary(
                id = "20260914_152925_626312",
                title = "Fix the parser",
                preview = "",
                startedAt = 1_789_392_566.9,
                messageCount = 8,
                source = "tui",
            ),
        )

        val report = facts.toReport()
        assertTrue(report.contains("Runtime ID: ab12cd34"))
        assertTrue(report.contains("Stored ID: 20260914_152925_626312"))
        assertTrue(report.contains("Model: codex/gpt-5.6-luna"))
        assertTrue(report.contains("Messages: 8"))
        assertTrue(report.contains("Fast tier: on"))
        // A field the server never sent must not appear as an empty line.
        assertTrue("a missing project has no line", !report.contains("Project:"))
        assertTrue("no line is just a label and a colon", !report.contains(Regex("(?m)^[A-Za-z ]+: $")))
    }

    @Test
    fun theTwoSessionScopeFieldsAreReadOffTheWirePayload() {
        // These arrived in the live `session.info` payload and were being dropped on
        // the floor: without them two sessions in different checkouts read the same.
        val parsed = SessionInfo.from(
            buildJsonObject {
                put("model", "codex/gpt-5.6-luna")
                put("project", "materialagent")
                put("terminal_backend", "local")
                put("cwd", "/home/user/workspace")
            },
        )!!

        assertEquals("materialagent", parsed.project)
        assertEquals("local", parsed.terminalBackend)
    }
}
