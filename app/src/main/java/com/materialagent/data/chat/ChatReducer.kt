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
        return when (event.type) {
            GatewayEvent.MESSAGE_START -> state.copy(running = true, turnStartedAt = state.turnStartedAt ?: now)

            GatewayEvent.MESSAGE_DELTA -> {
                val chunk = event.text.orEmpty()
                if (chunk.isEmpty()) return state
                val next = openAssistant(state, stamp)
                updateLast(next) { entry ->
                    entry.copy(
                        text = (entry.text + chunk).takeLast(MAX_STREAM_CHARS),
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
                        entry.copy(
                            text = if (finalText.length >= entry.text.length) finalText else entry.text,
                            statusLine = "",
                        )
                    }
                }
                val finished = updateLast(base) { entry ->
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
                title = payload.strAny("description", "command").orEmpty().ifBlank { "Approval required" },
                detail = payload.str("command").orEmpty(),
                allowPermanent = payload.bool("allow_permanent") ?: false,
                stamp = stamp,
            )

            GatewayEvent.CLARIFY_REQUEST -> state.appendInteraction(
                kind = EntryKind.CLARIFY,
                requestId = event.requestId ?: "clarify-${stamp}",
                title = payload.strAny("question", "prompt").orEmpty().ifBlank { "The agent has a question" },
                detail = "",
                choices = payload.arr("choices")?.mapNotNull { it.strOrNull() }.orEmpty(),
                multiSelect = payload.bool("multi_select") ?: false,
                stamp = stamp,
            )

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
    }

    /** Rebuilds a transcript from durable `session.history` rows. */
    fun fromHistory(
        state: ChatTranscript,
        rows: List<HistoryRow>,
        sessionId: String,
        storedSessionId: String?,
        title: String,
        info: SessionInfo?,
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

                "assistant" -> entries.add(
                    TranscriptEntry(
                        id = row.rowId?.toString() ?: "h-assistant-$index",
                        kind = EntryKind.ASSISTANT,
                        text = row.text.orEmpty(),
                        timestamp = row.timestamp,
                        completedAt = row.timestamp,
                    ),
                )

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
        return state.copy(
            entries = entries,
            sessionId = sessionId,
            storedSessionId = storedSessionId,
            title = title.ifBlank { state.title },
            info = info ?: state.info,
            loadingHistory = false,
            historyError = null,
            running = info?.running ?: false,
        )
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
        allowPermanent: Boolean = false,
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
                allowPermanent = allowPermanent,
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
