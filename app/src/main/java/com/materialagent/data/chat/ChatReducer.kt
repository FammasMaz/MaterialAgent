package com.materialagent.data.chat

import com.materialagent.core.arr
import com.materialagent.core.bool
import com.materialagent.core.double
import com.materialagent.core.obj
import com.materialagent.core.objOrNull
import com.materialagent.core.strOrNull
import com.materialagent.core.renderResult
import com.materialagent.core.str
import com.materialagent.core.strAny
import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.HistoryRow
import com.materialagent.core.model.MediaMarkers
import com.materialagent.core.model.SessionInfo
import com.materialagent.core.model.Usage
import kotlinx.serialization.json.JsonObject

/**
 * The transcript reducer: a pure function from gateway events to chat state.
 *
 * Keeping this pure is what makes the chat reliable — it can be replayed from
 * a recorded event log in a unit test, and the live path and the reconnect
 * replay path go through exactly the same code.
 *
 * Ordering model: assistant output is a *sequence* of segments, not one blob.
 * When the agent calls a tool mid-turn the current assistant segment is sealed
 * and the tool lands between it and the next segment, so a transcript reads
 * top-to-bottom the way the agent actually worked.
 */
object ChatReducer {

    private const val MAX_STREAM_CHARS = 200_000

    fun submitUser(state: ChatTranscript, text: String, now: Double): ChatTranscript {
        val entry = TranscriptEntry(
            id = "user-${now.toLong()}-${text.hashCode()}",
            kind = EntryKind.USER,
            text = text,
            timestamp = now,
            status = EntryStatus.COMPLETE,
        )
        return state.copy(
            entries = sealStreaming(state).entries + entry,
            running = true,
            turnStartedAt = now,
            historyError = null,
        )
    }

    fun reduce(state: ChatTranscript, event: GatewayEvent, now: Double): ChatTranscript {
        val payload = event.payload
        val stamp = payload.double("timestamp") ?: now
        val next = reduceEvent(state, event, payload, stamp, now)
        // When a turn finishes, anything still waiting on the user is finished
        // too: the gateway fails an unanswered approval closed, so nothing is
        // left listening for a late answer.
        return if (state.running && !next.running) expirePendingInteractions(next, stamp) else next
    }

    private fun reduceEvent(
        state: ChatTranscript,
        event: GatewayEvent,
        payload: JsonObject?,
        stamp: Double,
        now: Double,
    ): ChatTranscript = when (event.type) {
            GatewayEvent.MESSAGE_START -> state.copy(running = true, turnStartedAt = state.turnStartedAt ?: now)

            GatewayEvent.MESSAGE_DELTA -> {
                val chunk = event.text.orEmpty()
                if (chunk.isEmpty()) return state
                val next = openAssistant(state, stamp)
                updateLast(next) { entry ->
                    // A marker can be split across two deltas ("MEDIA:/tmp/ver"
                    // then "sion.pdf"), so the raw stream is kept and re-stripped
                    // in full each time — a half-arrived path is recognised
                    // rather than shown.
                    val raw = ((entry.streamRaw ?: entry.text) + chunk).takeLast(MAX_STREAM_CHARS)
                    entry.copy(
                        text = MediaMarkers.stripStreaming(raw),
                        streamRaw = raw,
                        status = EntryStatus.STREAMING,
                        statusLine = "",
                    )
                }
            }

            GatewayEvent.MESSAGE_INTERIM -> {
                val text = event.text.orEmpty()
                if (text.isBlank()) return state
                val sealed = sealStreaming(state)
                val entry = TranscriptEntry(
                    id = "interim-${sealed.entries.size}-${stamp}",
                    kind = EntryKind.ASSISTANT,
                    text = text,
                    timestamp = stamp,
                    completedAt = stamp,
                    interim = true,
                )
                sealed.copy(entries = sealed.entries + entry)
            }

            GatewayEvent.MESSAGE_COMPLETE -> {
                val finalText = event.text
                val usage = Usage.from(payload.obj("usage"))
                val failed = event.status == "error"
                val errorText = payload.strAny("error", "message", "failure_reason")
                val base = if (finalText.isNullOrEmpty()) state else {
                    val opened = openAssistant(state, stamp)
                    updateLast(opened) { entry ->
                        // The complete payload is authoritative; deltas can drop.
                        val streamed = (entry.streamRaw ?: entry.text).length
                        entry.copy(
                            text = if (finalText.length >= streamed) finalText else entry.text,
                            statusLine = "",
                        )
                    }
                }
                // The complete text is the turn's only authoritative copy, so it
                // is where markers become attachments: cleaned prose and the refs
                // land on the entry together and nothing downstream sees a path.
                val withMedia = updateLast(base) { entry ->
                    val extracted = MediaMarkers.extract(entry.text)
                    entry.copy(
                        text = extracted.text,
                        media = extracted.media,
                        // The raw stream existed only to hide markers while they
                        // were arriving; the final text supersedes it.
                        streamRaw = null,
                    )
                }
                val finished = updateLast(withMedia) { entry ->
                    entry.copy(
                        reasoning = payload.str("reasoning")?.takeIf { it.isNotBlank() } ?: entry.reasoning,
                        status = if (failed) EntryStatus.ERROR else EntryStatus.COMPLETE,
                        completedAt = stamp,
                        error = if (failed) errorText ?: "The turn failed" else null,
                    )
                }
                markToolsSettled(finished).copy(
                    running = false,
                    turnStartedAt = null,
                    usage = usage ?: finished.usage,
                )
            }

            GatewayEvent.THINKING_DELTA -> {
                val text = event.text.orEmpty()
                if (text.isBlank()) return state
                val opened = openAssistant(state, stamp)
                updateLast(opened) { entry ->
                    entry.copy(statusLine = text, status = EntryStatus.STREAMING)
                }
            }

            GatewayEvent.REASONING_DELTA -> {
                val chunk = event.text.orEmpty()
                if (chunk.isEmpty()) return state
                val opened = openAssistant(state, stamp)
                updateLast(opened) { entry ->
                    entry.copy(
                        reasoning = (entry.reasoning + chunk).takeLast(MAX_STREAM_CHARS),
                        status = EntryStatus.STREAMING,
                    )
                }
            }

            GatewayEvent.TOOL_GENERATING -> {
                val name = event.name ?: "tool"
                val existing = state.entries.indexOfLast { it.kind == EntryKind.TOOL && it.tool?.running == true && it.tool.name == name }
                if (existing >= 0) return state
                val sealed = sealStreaming(state)
                val entry = TranscriptEntry(
                    id = "tool-gen-$name-${stamp}",
                    kind = EntryKind.TOOL,
                    timestamp = stamp,
                    tool = ToolInfo(
                        id = "gen-$name",
                        name = name,
                        context = "",
                        args = null,
                        result = null,
                        durationS = null,
                        running = true,
                    ),
                )
                sealed.copy(entries = sealed.entries + entry)
            }

            GatewayEvent.TOOL_START -> {
                val toolId = event.toolId ?: "tool-${stamp}"
                val name = event.name ?: "tool"
                val context = payload.strAny("context", "preview").orEmpty()
                val args = payload.obj("args")
                val sealed = sealStreaming(state)
                // A tool.generating placeholder for the same tool is upgraded in
                // place so the row doesn't flicker through two identities.
                val placeholder = sealed.entries.indexOfLast {
                    it.kind == EntryKind.TOOL && it.tool?.running == true && it.tool.name == name && it.tool.id.startsWith("gen-")
                }
                val entry = TranscriptEntry(
                    id = if (placeholder >= 0) sealed.entries[placeholder].id else toolId,
                    kind = EntryKind.TOOL,
                    timestamp = sealed.entries.getOrNull(placeholder)?.timestamp ?: stamp,
                    tool = ToolInfo(
                        id = toolId,
                        name = name,
                        context = context,
                        args = args,
                        result = null,
                        durationS = null,
                        running = true,
                        preview = payload.str("preview"),
                    ),
                )
                val entries = sealed.entries.toMutableList()
                if (placeholder >= 0) entries[placeholder] = entry else entries.add(entry)
                sealed.copy(entries = entries)
            }

            GatewayEvent.TOOL_COMPLETE -> {
                val toolId = event.toolId
                val name = event.name ?: "tool"
                val args = payload.obj("args")
                val result = payload?.get("result").renderResult().take(MAX_STREAM_CHARS)
                val duration = payload.double("duration_s")
                val entries = state.entries.toMutableList()
                val index = entries.indexOfLast {
                    it.kind == EntryKind.TOOL && it.tool != null &&
                        (it.tool.id == toolId || (toolId == null && it.tool.name == name && it.tool.running))
                }
                if (index >= 0) {
                    val existing = entries[index]
                    entries[index] = existing.copy(
                        completedAt = stamp,
                        tool = existing.tool!!.copy(
                            args = args ?: existing.tool.args,
                            result = result,
                            durationS = duration,
                            running = false,
                        ),
                    )
                } else {
                    entries.add(
                        TranscriptEntry(
                            id = toolId ?: "tool-${stamp}",
                            kind = EntryKind.TOOL,
                            timestamp = stamp,
                            completedAt = stamp,
                            tool = ToolInfo(
                                id = toolId ?: "tool-${stamp}",
                                name = name,
                                context = payload.strAny("context", "preview").orEmpty(),
                                args = args,
                                result = result,
                                durationS = duration,
                                running = false,
                            ),
                        ),
                    )
                }
                state.copy(entries = entries)
            }

            GatewayEvent.TODO_UPDATED -> {
                val todos = payload.arr("todos")?.mapNotNull { item ->
                    val obj = item.objOrNull() ?: return@mapNotNull null
                    val text = obj.strAny("text", "content", "task") ?: return@mapNotNull null
                    TodoItem(text = text, status = obj.str("status") ?: "pending")
                }.orEmpty()
                if (todos.isEmpty() && state.entries.none { it.kind == EntryKind.TODOS }) return state
                val entries = state.entries.toMutableList()
                val index = entries.indexOfLast { it.kind == EntryKind.TODOS }
                val entry = TranscriptEntry(
                    id = "todos",
                    kind = EntryKind.TODOS,
                    todos = todos,
                    timestamp = stamp,
                )
                if (index >= 0) entries[index] = entry else entries.add(entry)
                state.copy(entries = entries)
            }

            GatewayEvent.STATUS_UPDATE -> {
                val text = event.text.orEmpty()
                if (text.isBlank()) return state
                state.copy(
                    entries = sealStreaming(state).entries + TranscriptEntry(
                        id = "note-${state.entries.size}-${stamp}",
                        kind = EntryKind.NOTE,
                        text = text,
                        timestamp = stamp,
                    ),
                )
            }

            GatewayEvent.APPROVAL_REQUEST -> state.appendInteraction(
                kind = EntryKind.APPROVAL,
                requestId = event.requestId ?: "approval-${stamp}",
                // `description` is the policy's reason ("delete in root path"),
                // which reads better as the heading than the command itself.
                title = payload.strAny("description", "command").orEmpty().ifBlank { "Approval required" },
                detail = payload.str("command").orEmpty(),
                // The server decides what may be offered — `once`, `session`,
                // `always`, `deny` — and omits `always` when the policy forbids
                // it. Dropping these left the card with a blank text field and no
                // way to answer.
                choices = payload.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                stamp = stamp,
            )

            GatewayEvent.CLARIFY_REQUEST -> {
                // `questions[]` is the real shape — the older flat
                // `question`/`choices` keys are still read as a fallback so a
                // single-question variant keeps working.
                val questions = clarifyQuestions(payload)
                state.appendInteraction(
                    kind = EntryKind.CLARIFY,
                    requestId = event.requestId ?: "clarify-${stamp}",
                    // One question reads best as the heading itself. Several
                    // would repeat the first one under a heading that already
                    // showed it, so the heading goes generic and each question
                    // keeps its own line below.
                    title = if (questions.size > 1) {
                        "The agent has ${questions.size} questions"
                    } else {
                        questions.firstOrNull()?.text?.ifBlank { null }
                            ?: payload.strAny("question", "prompt").orEmpty()
                                .ifBlank { "The agent has a question" }
                    },
                    detail = "",
                    choices = payload.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                    multiSelect = payload.bool("multi_select") ?: false,
                    questions = questions,
                    stamp = stamp,
                )
            }

            GatewayEvent.SUDO_REQUEST -> state.appendInteraction(
                kind = EntryKind.SUDO,
                requestId = event.requestId ?: "sudo-${stamp}",
                title = payload.strAny("prompt", "description").orEmpty().ifBlank { "Password required" },
                detail = payload.str("command").orEmpty(),
                stamp = stamp,
            )

            GatewayEvent.SECRET_REQUEST -> state.appendInteraction(
                kind = EntryKind.SECRET,
                requestId = event.requestId ?: "secret-${stamp}",
                title = payload.strAny("prompt", "env_var").orEmpty().ifBlank { "Credential required" },
                detail = payload.str("env_var").orEmpty(),
                stamp = stamp,
            )

            GatewayEvent.SESSION_INFO -> {
                val info = SessionInfo.from(payload)
                state.copy(
                    info = info ?: state.info,
                    storedSessionId = info?.storedSessionId ?: state.storedSessionId,
                    title = info?.title?.takeIf { it.isNotBlank() } ?: state.title,
                    running = info?.running ?: state.running,
                    turnStartedAt = info?.turnStartedAt,
                    usage = info?.usage ?: state.usage,
                )
            }

            GatewayEvent.SESSION_TITLE -> {
                val title = payload.str("title")
                if (title.isNullOrBlank()) state else state.copy(title = title)
            }

            GatewayEvent.SESSION_USAGE -> state.copy(usage = Usage.from(payload.obj("usage") ?: payload) ?: state.usage)

            GatewayEvent.TURN_ERROR -> {
                val message = payload.strAny("text", "message").orEmpty().ifBlank { "The turn failed" }
                state.copy(
                    running = false,
                    turnStartedAt = null,
                    entries = state.entries + TranscriptEntry(
                        id = "err-${state.entries.size}-${stamp}",
                        kind = EntryKind.NOTE,
                        text = message,
                        timestamp = stamp,
                        status = EntryStatus.ERROR,
                    ),
                )
            }

            else -> state
    }

    /**
     * Retires interactions that a finished turn left unanswered.
     *
     * The gateway fails an unanswered approval closed — a dropped socket or a
     * timeout denies it and the tool returns "blocked" — after which the card can
     * no longer be answered. Leaving it on screen with live buttons offered a
     * dead action, so the turn ending is what closes it.
     */
    fun expirePendingInteractions(state: ChatTranscript, stamp: Double): ChatTranscript {
        if (state.entries.none { it.interactive?.isPending == true }) return state
        return state.copy(
            entries = state.entries.map { entry ->
                val request = entry.interactive
                if (request == null || !request.isPending) entry
                else entry.copy(interactive = request.copy(expired = true))
            },
        )
    }

    /** Rebuilds a transcript from durable `session.history` rows. */
    fun fromHistory(
        state: ChatTranscript,
        rows: List<HistoryRow>,
        sessionId: String,
        storedSessionId: String?,
        title: String,
        info: SessionInfo?,
        pendingApproval: JsonObject? = null,
        pendingClarify: JsonObject? = null,
    ): ChatTranscript {
        val entries = mutableListOf<TranscriptEntry>()
        rows.forEachIndexed { index, row ->
            when (row.role) {
                "user" -> entries.add(
                    TranscriptEntry(
                        id = row.rowId?.toString() ?: "h-user-$index",
                        kind = EntryKind.USER,
                        text = row.text.orEmpty(),
                        timestamp = row.timestamp,
                    ),
                )

                "assistant" -> {
                    // History rows carry the same markers the live text did, so a
                    // reconnected transcript must resolve them the same way.
                    val extracted = MediaMarkers.extract(row.text.orEmpty())
                    entries.add(
                        TranscriptEntry(
                            id = row.rowId?.toString() ?: "h-assistant-$index",
                            kind = EntryKind.ASSISTANT,
                            text = extracted.text,
                            media = extracted.media,
                            timestamp = row.timestamp,
                            completedAt = row.timestamp,
                        ),
                    )
                }

                "tool" -> entries.add(
                    TranscriptEntry(
                        id = "h-tool-$index-${row.toolName}",
                        kind = EntryKind.TOOL,
                        timestamp = row.timestamp,
                        completedAt = row.timestamp,
                        tool = ToolInfo(
                            id = "h-tool-$index",
                            name = row.toolName ?: "tool",
                            context = row.toolContext.orEmpty(),
                            args = row.toolArgs,
                            result = null,
                            fromHistory = true,
                            durationS = null,
                            running = false,
                        ),
                    ),
                )

                "system" -> entries.add(
                    TranscriptEntry(
                        id = "h-system-$index",
                        kind = EntryKind.NOTE,
                        text = row.text.orEmpty(),
                        timestamp = row.timestamp,
                    ),
                )
            }
        }
        // A replayed interaction is rendered exactly like a live one: the gateway
        // builds both from the same payload, and the request_id inside it is what
        // the response is keyed by.
        var restored = state
        pendingApproval?.let { approval ->
            restored = restored.appendInteraction(
                kind = EntryKind.APPROVAL,
                requestId = approval.str("request_id") ?: "approval-replayed",
                title = approval.strAny("description", "command").orEmpty().ifBlank { "Approval required" },
                detail = approval.str("command").orEmpty(),
                choices = approval.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                stamp = approval.double("timestamp") ?: 0.0,
            )
        }
        pendingClarify?.let { clarify ->
            restored = restored.appendInteraction(
                kind = EntryKind.CLARIFY,
                requestId = clarify.str("request_id") ?: "clarify-replayed",
                title = clarifyQuestions(clarify).firstOrNull()?.text?.ifBlank { null }
                    ?: clarify.strAny("question", "prompt").orEmpty().ifBlank { "The agent has a question" },
                detail = "",
                choices = clarify.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                multiSelect = clarify.bool("multi_select") ?: false,
                questions = clarifyQuestions(clarify),
                stamp = clarify.double("timestamp") ?: 0.0,
            )
        }
        return restored.copy(
            entries = restored.entries + entries,
            sessionId = sessionId,
            storedSessionId = storedSessionId,
            title = title.ifBlank { state.title },
            info = info ?: state.info,
            loadingHistory = false,
            historyError = null,
            running = info?.running ?: false,
        )
    }

    /**
     * Records one question of a batch as answered.
     *
     * The card stays up until the last one, because the agent is still blocked —
     * the gateway only releases the tool once every `qid` is accounted for.
     */
    fun markQuestionAnswered(
        state: ChatTranscript,
        requestId: String,
        questionId: String,
        answer: String,
    ): ChatTranscript = updateEntry(state, requestId) { entry ->
        val request = entry.interactive ?: return@updateEntry entry
        val questions = request.questions.map { question ->
            if (question.id == questionId) question.copy(answer = answer) else question
        }
        // A single-question batch reads better as "You answered: X" than as a
        // one-row list, so keep `answer` in step with the only question.
        val only = if (questions.size == 1) questions.first().answer else request.answer
        entry.copy(interactive = request.copy(questions = questions, answer = only))
    }

    /**
     * Closes a request the server no longer holds.
     *
     * `clarify.respond` answers `{"status":"expired"}` when the request is gone —
     * already resolved, or timed out server-side. Treating that as success is how
     * a card ends up claiming an answer the agent never received.
     */
    fun markInteractionExpired(state: ChatTranscript, requestId: String): ChatTranscript =
        updateEntry(state, requestId) { entry ->
            entry.copy(interactive = entry.interactive?.copy(expired = true))
        }

    /** Reads the gateway's `questions[]`, tolerating the older flat shape. */
    private fun clarifyQuestions(source: JsonObject?): List<ClarifyQuestion> {
        val raw = source.arr("questions") ?: return emptyList()
        return raw.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            ClarifyQuestion(
                id = obj.str("qid").orEmpty().ifBlank { "q$index" },
                text = obj.strAny("question", "prompt").orEmpty(),
                choices = obj.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                multiSelect = obj.bool("multi_select") ?: false,
            )
        }
    }

    fun markInteractionAnswered(
        state: ChatTranscript,
        requestId: String,
        answer: String,
    ): ChatTranscript = updateEntry(state, requestId) { entry ->
        entry.copy(interactive = entry.interactive?.copy(answer = answer))
    }

    fun failInteraction(state: ChatTranscript, requestId: String, message: String): ChatTranscript =
        updateEntry(state, requestId) { entry ->
            entry.copy(
                interactive = entry.interactive?.copy(answer = "⚠ $message"),
                status = EntryStatus.ERROR,
            )
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ChatTranscript.appendInteraction(
        kind: EntryKind,
        requestId: String,
        title: String,
        detail: String,
        choices: List<String> = emptyList(),
        multiSelect: Boolean = false,
        questions: List<ClarifyQuestion> = emptyList(),
        stamp: Double,
    ): ChatTranscript {
        if (entries.any { it.interactive?.requestId == requestId }) return this
        val entry = TranscriptEntry(
            id = requestId,
            kind = kind,
            timestamp = stamp,
            interactive = InteractiveRequest(
                requestId = requestId,
                kind = kind,
                title = title,
                detail = detail,
                choices = choices,
                multiSelect = multiSelect,
                questions = questions,
            ),
        )
        return copy(entries = sealStreaming(this).entries + entry)
    }

    /** The open assistant segment is the last entry when it is still streaming. */
    private fun openAssistant(state: ChatTranscript, stamp: Double): ChatTranscript {
        val last = state.entries.lastOrNull()
        if (last != null && last.kind == EntryKind.ASSISTANT && last.status == EntryStatus.STREAMING) {
            return state
        }
        val entry = TranscriptEntry(
            id = "assistant-${state.entries.size}-${stamp.toLong()}",
            kind = EntryKind.ASSISTANT,
            timestamp = stamp,
            status = EntryStatus.STREAMING,
        )
        return state.copy(entries = state.entries + entry)
    }

    /**
     * Closes any streaming assistant segment. Empty segments are dropped so a
     * `thinking.delta` that never produced text doesn't leave a blank bubble.
     */
    private fun sealStreaming(state: ChatTranscript): ChatTranscript {
        val index = state.entries.indexOfLast { it.status == EntryStatus.STREAMING }
        if (index < 0) return state
        val entries = state.entries.toMutableList()
        val entry = entries[index]
        if (!entry.hasContent) {
            entries.removeAt(index)
        } else {
            entries[index] = entry.copy(status = EntryStatus.COMPLETE)
        }
        return state.copy(entries = entries)
    }

    private fun markToolsSettled(state: ChatTranscript): ChatTranscript {
        if (state.entries.none { it.tool?.running == true }) {
            return if (state.entries.any { it.status == EntryStatus.STREAMING }) sealStreaming(state) else state
        }
        val entries = state.entries.map { entry ->
            if (entry.tool?.running == true) {
                entry.copy(tool = entry.tool.copy(running = false))
            } else {
                entry
            }
        }
        val sealed = state.copy(entries = entries)
        return if (sealed.entries.any { it.status == EntryStatus.STREAMING }) sealStreaming(sealed) else sealed
    }

    private fun updateLast(state: ChatTranscript, transform: (TranscriptEntry) -> TranscriptEntry): ChatTranscript {
        val index = state.entries.indexOfLast { it.kind == EntryKind.ASSISTANT && it.status == EntryStatus.STREAMING }
        if (index < 0) return state
        val entries = state.entries.toMutableList()
        entries[index] = transform(entries[index])
        return state.copy(entries = entries)
    }

    private fun updateEntry(
        state: ChatTranscript,
        id: String,
        transform: (TranscriptEntry) -> TranscriptEntry,
    ): ChatTranscript {
        val index = state.entries.indexOfLast { it.id == id || it.interactive?.requestId == id }
        if (index < 0) return state
        val entries = state.entries.toMutableList()
        entries[index] = transform(entries[index])
        return state.copy(entries = entries)
    }
}
