package com.materialagent.data.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.HistoryRow
import com.materialagent.core.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [ChatReducer], the pure event→transcript function.
 *
 * The invariant that matters: a turn's assistant output is one entry that
 * absorbs every `message.delta` and lands on exactly one terminal status, tool
 * rows flip running→done in place, the model's reasoning is stored apart from
 * its answer, a new `message.start` begins a fresh segment instead of gluing
 * onto the previous one, and `running` tracks the turn's lifetime.
 */
class ChatReducerTest {

    // ── a whole user turn ───────────────────────────────────────────────────

    @Test
    fun userTurnProducesUserThenOneStreamedAssistantEntry() {
        var state = ChatReducer.submitUser(ChatTranscript(), "Hi", now = 1.0)
        assertEquals(1, state.entries.size)
        assertEquals(EntryKind.USER, state.entries[0].kind)
        assertEquals("Hi", state.entries[0].text)
        assertTrue(state.running)

        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_START, seq = 2), 2.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "Hel"), seq = 3), 3.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "lo"), seq = 4), 4.0)

        assertEquals(2, state.entries.size)
        val assistant = state.entries[1]
        assertEquals(EntryKind.ASSISTANT, assistant.kind)
        assertEquals("Hello", assistant.text)
        assertEquals(EntryStatus.STREAMING, assistant.status)

        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "Hello"), seq = 5), 5.0)

        assertEquals(2, state.entries.size)
        assertEquals("Hello", state.entries[1].text)
        assertEquals(EntryStatus.COMPLETE, state.entries[1].status)
        assertNotNull(state.entries[1].completedAt)
        assertFalse(state.running)
        assertNull(state.turnStartedAt)
    }

    @Test
    fun completeTextIsAuthoritativeOverAccumulatedDeltas() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "He"), seq = 2), 2.0)
        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "Hello there"), seq = 3),
            3.0,
        )
        assertEquals("Hello there", state.entries.last().text)
    }

    @Test
    fun aShorterCompleteTextDoesNotTruncateStreamedAnswer() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "long answer"), seq = 2), 2.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "long"), seq = 3), 3.0)
        assertEquals("long answer", state.entries.last().text)
    }

    // ── reasoning and status lines ──────────────────────────────────────────

    @Test
    fun reasoningDeltasAccumulateSeparatelyFromAnswerText() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.REASONING_DELTA, p("text" to "think "), seq = 2), 2.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.REASONING_DELTA, p("text" to "more"), seq = 3), 3.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "answer"), seq = 4), 4.0)

        assertEquals(2, state.entries.size)
        val assistant = state.entries.last()
        assertEquals("think more", assistant.reasoning)
        assertEquals("answer", assistant.text)
    }

    @Test
    fun thinkingDeltaSetsTheStatusLineNotTheBody() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.THINKING_DELTA, p("text" to "Reading files"), seq = 2), 2.0)

        val assistant = state.entries.last()
        assertEquals("Reading files", assistant.statusLine)
        assertEquals("", assistant.text)
        assertEquals(EntryStatus.STREAMING, assistant.status)
    }

    @Test
    fun messageCompleteClearsTheStatusLine() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.THINKING_DELTA, p("text" to "Reading"), seq = 2), 2.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "done"), seq = 3), 3.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "done"), seq = 4), 4.0)
        assertEquals("", state.entries.last().statusLine)
    }

    // ── tools ───────────────────────────────────────────────────────────────

    @Test
    fun toolCallFlipsFromRunningToDoneCarryingItsResult() {
        var state = ChatReducer.submitUser(ChatTranscript(), "list files", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "Running"), seq = 2), 2.0)
        state = ChatReducer.reduce(
            state,
            event(
                GatewayEvent.TOOL_START,
                p("name" to "terminal", "tool_id" to "t1", "context" to "ls -la", "args" to p("command" to "ls -la")),
                seq = 3,
            ),
            3.0,
        )

        // Starting a tool seals the assistant segment before it.
        assertEquals(EntryStatus.COMPLETE, state.entries[1].status)
        val tool = state.entries.last()
        assertEquals(EntryKind.TOOL, tool.kind)
        assertNotNull(tool.tool)
        assertTrue(tool.tool!!.running)
        assertEquals("terminal", tool.tool!!.name)
        assertEquals("t1", tool.tool!!.id)

        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.TOOL_COMPLETE, p("name" to "terminal", "tool_id" to "t1", "result" to "file.txt"), seq = 4),
            4.0,
        )

        assertEquals(3, state.entries.size)
        val done = state.entries.last()
        assertEquals(EntryKind.TOOL, done.kind)
        assertFalse(done.tool!!.running)
        assertEquals("file.txt", done.tool!!.result)
        assertNotNull(done.completedAt)
    }

    @Test
    fun toolGeneratingPlaceholderIsUpgradedInPlaceByToolStart() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.TOOL_GENERATING, p("name" to "terminal"), seq = 2), 2.0)
        assertEquals(2, state.entries.size)

        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.TOOL_START, p("name" to "terminal", "tool_id" to "real-1"), seq = 3),
            3.0,
        )

        assertEquals(2, state.entries.size)
        assertEquals("real-1", state.entries.last().tool!!.id)
    }

    @Test
    fun turnCompletionSettlesAnyToolStillRunning() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.TOOL_START, p("name" to "terminal", "tool_id" to "t1"), seq = 2),
            2.0,
        )
        assertTrue(state.entries.last().tool!!.running)

        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "done"), seq = 3), 3.0)

        val tool = state.entries.last { it.kind == EntryKind.TOOL }
        assertFalse(tool.tool!!.running)
        assertFalse(state.running)
    }

    // ── failure ─────────────────────────────────────────────────────────────

    @Test
    fun errorStatusMarksAssistantEntryErrorRatherThanComplete() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "partial"), seq = 2), 2.0)
        state = ChatReducer.reduce(
            state,
            event(
                GatewayEvent.MESSAGE_COMPLETE,
                p("text" to "partial", "status" to "error", "error" to "model refused"),
                seq = 3,
            ),
            3.0,
        )

        val assistant = state.entries.last()
        assertEquals(EntryStatus.ERROR, assistant.status)
        assertEquals("model refused", assistant.error)
        assertFalse(state.running)
    }

    @Test
    fun errorStatusWithoutDetailUsesFallbackMessage() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "x"), seq = 2), 2.0)
        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "x", "status" to "error"), seq = 3),
            3.0,
        )
        assertEquals(EntryStatus.ERROR, state.entries.last().status)
        assertEquals("The turn failed", state.entries.last().error)
    }

    // ── segment boundaries ──────────────────────────────────────────────────

    @Test
    fun secondMessageStartAfterCompletedTurnBeginsANewEntry() {
        var state = ChatReducer.submitUser(ChatTranscript(), "one", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "A"), seq = 2), 2.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "A"), seq = 3), 3.0)
        val firstId = state.entries[1].id

        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_START, seq = 4), 4.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "B"), seq = 5), 5.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_COMPLETE, p("text" to "B"), seq = 6), 6.0)

        assertEquals(3, state.entries.size)
        assertEquals("A", state.entries[1].text)
        assertEquals("B", state.entries[2].text)
        assertNotEquals(firstId, state.entries[2].id)
        assertTrue(state.entries.any { it.kind == EntryKind.USER })
    }

    @Test
    fun deltaAfterToolGoesIntoANewAssistantSegment() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "before"), seq = 2), 2.0)
        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.TOOL_START, p("name" to "terminal", "tool_id" to "t1"), seq = 3),
            3.0,
        )
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "after"), seq = 4), 4.0)

        assertEquals(4, state.entries.size)
        assertEquals(EntryKind.ASSISTANT, state.entries[1].kind)
        assertEquals("before", state.entries[1].text)
        assertEquals(EntryKind.TOOL, state.entries[2].kind)
        assertEquals(EntryKind.ASSISTANT, state.entries[3].kind)
        assertEquals("after", state.entries[3].text)
    }

    @Test
    fun emptyThinkingSegmentIsDroppedWhenSealed() {
        var state = ChatReducer.submitUser(ChatTranscript(), "q", 1.0)
        state = ChatReducer.reduce(state, event(GatewayEvent.THINKING_DELTA, p("text" to "…"), seq = 2), 2.0)
        assertEquals(2, state.entries.size)

        // A tool with no answer text around it must not leave a blank bubble.
        state = ChatReducer.reduce(
            state,
            event(GatewayEvent.TOOL_START, p("name" to "terminal", "tool_id" to "t1"), seq = 3),
            3.0,
        )

        assertEquals(2, state.entries.size)
        assertEquals(EntryKind.TOOL, state.entries[1].kind)
    }

    // ── submitUser ──────────────────────────────────────────────────────────

    @Test
    fun submitUserAppendsExactlyOneUserEntryAndStartsTheTurn() {
        var state = ChatReducer.submitUser(ChatTranscript(), "hello", now = 5.0)

        assertEquals(1, state.entries.size)
        assertEquals(EntryKind.USER, state.entries[0].kind)
        assertEquals("hello", state.entries[0].text)
        assertEquals(5.0, state.entries[0].timestamp!!, 0.0)
        assertTrue(state.running)
        assertEquals(5.0, state.turnStartedAt!!, 0.0)

        // A second submit seals the still-open assistant and appends one more row.
        state = ChatReducer.reduce(state, event(GatewayEvent.MESSAGE_DELTA, p("text" to "reply"), seq = 1), 6.0)
        val beforeSecond = state.entries.size
        state = ChatReducer.submitUser(state, "again", now = 7.0)

        assertEquals(beforeSecond + 1, state.entries.size)
        assertEquals(EntryKind.USER, state.entries.last().kind)
        assertEquals("again", state.entries.last().text)
        assertEquals(EntryStatus.COMPLETE, state.entries[beforeSecond - 1].status)
        assertTrue(state.running)
    }

    @Test
    fun submitUserClearsAPreviousHistoryError() {
        val state = ChatTranscript(historyError = "boom")
        val after = ChatReducer.submitUser(state, "hi", 1.0)
        assertNull(after.historyError)
    }

    // ── fromHistory ─────────────────────────────────────────────────────────

    @Test
    fun fromHistoryMapsRowsToEntryKindsAndPassesThroughIdentity() {
        val rows = listOf(
            HistoryRow(role = "user", text = "hi", toolName = null, toolContext = null, toolArgs = null, timestamp = 1.0, rowId = 10L),
            HistoryRow(role = "assistant", text = "hello", toolName = null, toolContext = null, toolArgs = null, timestamp = 2.0, rowId = 11L),
            HistoryRow(role = "tool", text = null, toolName = "terminal", toolContext = "ls", toolArgs = null, timestamp = 3.0, rowId = 12L),
            HistoryRow(role = "system", text = "booted", toolName = null, toolContext = null, toolArgs = null, timestamp = 4.0, rowId = 13L),
        )

        val state = ChatReducer.fromHistory(
            state = ChatTranscript(),
            rows = rows,
            sessionId = "s-1",
            storedSessionId = "stored-1",
            title = "My session",
            info = null,
        )

        assertEquals(4, state.entries.size)
        assertEquals(EntryKind.USER, state.entries[0].kind)
        assertEquals("hi", state.entries[0].text)
        assertEquals(EntryKind.ASSISTANT, state.entries[1].kind)
        assertEquals("hello", state.entries[1].text)
        assertEquals(EntryKind.TOOL, state.entries[2].kind)
        assertEquals("terminal", state.entries[2].tool!!.name)
        assertEquals("ls", state.entries[2].tool!!.context)
        assertFalse(state.entries[2].tool!!.running)
        assertEquals(EntryKind.NOTE, state.entries[3].kind)
        assertEquals("booted", state.entries[3].text)

        assertEquals("s-1", state.sessionId)
        assertEquals("stored-1", state.storedSessionId)
        assertEquals("My session", state.title)
        assertFalse(state.loadingHistory)
        assertNull(state.historyError)
        assertFalse(state.running)
    }

    @Test
    fun fromHistoryKeepsExistingTitleWhenServerSendsBlankAndAdoptsRunningState() {
        val running = SessionInfo(
            model = null, provider = null, reasoningEffort = null, serviceTier = null,
            fast = false, yolo = false, approvalMode = null, cwd = null, branch = null,
            title = null, storedSessionId = null, profileName = null, personality = null,
            running = true, turnStartedAt = null, version = null, usage = null,
            tools = emptyMap(), skills = emptyMap(), mcpServers = null,
        )
        val state = ChatReducer.fromHistory(
            state = ChatTranscript(title = "keep me"),
            rows = emptyList(),
            sessionId = "s-2",
            storedSessionId = null,
            title = "",
            info = running,
        )

        assertEquals("keep me", state.title)
        assertTrue(state.running)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun anApprovalCarriesTheServersChoices() {
        // The card can only offer the answers the server named; dropping these
        // left it with a blank text field and no way to approve anything.
        val state = ChatReducer.reduce(
            ChatTranscript(),
            event(
                GatewayEvent.APPROVAL_REQUEST,
                buildJsonObject {
                    put("request_id", "req-1")
                    put("description", "delete in root path")
                    put("command", "rm -rf /tmp/probe-dir")
                    put(
                        "choices",
                        buildJsonArray {
                            listOf("once", "session", "always", "deny").forEach { add(JsonPrimitive(it)) }
                        },
                    )
                },
            ),
            now = 100.0,
        )
        val request = state.entries.single().interactive!!
        assertEquals(EntryKind.APPROVAL, request.kind)
        assertEquals("delete in root path", request.title)
        assertEquals(listOf("once", "session", "always", "deny"), request.choices)
        assertTrue(request.isPending)
    }

    @Test
    fun aFinishedTurnClosesAnUnansweredRequest() {
        // The gateway fails an unanswered approval closed, so once the turn is
        // over the card must stop offering buttons for something nothing is
        // listening to any more.
        val pending = ChatReducer.reduce(
            ChatTranscript(),
            event(
                GatewayEvent.APPROVAL_REQUEST,
                buildJsonObject {
                    put("request_id", "req-1")
                    put("command", "rm -rf /tmp/probe-dir")
                },
            ),
            now = 100.0,
        )
        val running = ChatReducer.reduce(
            pending,
            event(GatewayEvent.MESSAGE_START, buildJsonObject { put("timestamp", 101.0) }),
            now = 101.0,
        )
        assertTrue("the request is still answerable mid-turn", running.pendingInteractions.isNotEmpty())

        val finished = ChatReducer.reduce(
            running,
            event(
                GatewayEvent.MESSAGE_COMPLETE,
                buildJsonObject { put("text", "The terminal blocked it."); put("timestamp", 102.0) },
            ),
            now = 102.0,
        )
        val request = finished.entries.mapNotNull { it.interactive }.single()
        assertTrue("the turn ending closes it", request.expired)
        assertFalse("and it stops counting as waiting", request.isPending)
        assertTrue("so it needs no attention", finished.pendingInteractions.isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun event(
        type: String,
        payload: JsonObject? = null,
        seq: Int? = null,
        sessionId: String? = "s-1",
    ): GatewayEvent = GatewayEvent(type = type, sessionId = sessionId, seq = seq, payload = payload)

    /** Compact `{"text": "…", "status": "…"}` builder for payloads. */
    private fun p(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is JsonElement -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
}
