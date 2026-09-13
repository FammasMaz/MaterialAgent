package com.materialagent.data.chat

import kotlinx.serialization.json.JsonObject

/** What kind of row a transcript entry is. Drives the surface's geometry. */
enum class EntryKind {
    USER,
    ASSISTANT,
    TOOL,
    NOTE,
    APPROVAL,
    CLARIFY,
    SUDO,
    SECRET,
    TODOS,
}

enum class EntryStatus { STREAMING, COMPLETE, ERROR, CANCELLED }

data class ToolInfo(
    val id: String,
    val name: String,
    val context: String,
    val args: JsonObject?,
    val result: String?,
    val durationS: Double?,
    val running: Boolean,
    val preview: String? = null,
)

data class TodoItem(
    val text: String,
    val status: String,
)

/** A blocking question the agent asked and is waiting on. */
data class InteractiveRequest(
    val requestId: String,
    val kind: EntryKind,
    val title: String,
    val detail: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    /** `approval.request.allow_permanent` — false when the policy forbids "always allow". */
    val allowPermanent: Boolean = false,
    /** Set to the chosen answer once the user responds. */
    val answer: String? = null,
) {
    val isPending: Boolean get() = answer == null
}

/** One row in the chat transcript. */
data class TranscriptEntry(
    val id: String,
    val kind: EntryKind,
    val text: String = "",
    val reasoning: String = "",
    /** Short status line from `thinking.delta` — the agent's spinner copy. */
    val statusLine: String = "",
    val tool: ToolInfo? = null,
    val interactive: InteractiveRequest? = null,
    val todos: List<TodoItem> = emptyList(),
    val timestamp: Double? = null,
    val completedAt: Double? = null,
    val status: EntryStatus = EntryStatus.COMPLETE,
    val error: String? = null,
    /** True for `message.interim` commentary that is not the turn's final answer. */
    val interim: Boolean = false,
) {
    val isStreaming: Boolean get() = status == EntryStatus.STREAMING
    val hasContent: Boolean get() = text.isNotBlank() || reasoning.isNotBlank()
}

/** Everything the chat screen renders, plus the live runtime state it needs. */
data class ChatTranscript(
    val entries: List<TranscriptEntry> = emptyList(),
    val sessionId: String? = null,
    val storedSessionId: String? = null,
    val title: String = "",
    val running: Boolean = false,
    val turnStartedAt: Double? = null,
    val info: com.materialagent.core.model.SessionInfo? = null,
    val usage: com.materialagent.core.model.Usage? = null,
    val loadingHistory: Boolean = false,
    val historyError: String? = null,
) {
    val pendingInteractions: List<InteractiveRequest>
        get() = entries.mapNotNull { it.interactive }.filter { it.isPending }

    /** Entries with content, newest last — what the list actually draws. */
    val visibleEntries: List<TranscriptEntry>
        get() = entries.filter { entry ->
            when (entry.kind) {
                EntryKind.ASSISTANT -> entry.hasContent || entry.isStreaming || entry.error != null
                EntryKind.NOTE -> entry.text.isNotBlank()
                EntryKind.TODOS -> entry.todos.isNotEmpty()
                else -> true
            }
        }
}
