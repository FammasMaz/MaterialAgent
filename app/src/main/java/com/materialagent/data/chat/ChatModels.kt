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
    /** True when this call was rebuilt from session history, which omits output. */
    val fromHistory: Boolean = false,
)

data class TodoItem(
    val text: String,
    val status: String,
)

/**
 * One question inside a clarify request.
 *
 * The gateway always sends `questions[]`, even for a single question, and it
 * answers them **per question**: `clarify.respond` wants the `qid` echoed back
 * as `question_id`, and the request only resolves once every question has been
 * answered — the last answer is what releases the agent.
 *
 * Answering without a `question_id` takes a different path inside the gateway
 * that still replies `{"status":"ok"}` while the tool itself gets nothing, which
 * is exactly how a two-question clarify came back saying "the clarify tool
 * returned no answer" with the typed text sitting in the card looking answered.
 */
data class ClarifyQuestion(
    val id: String,
    val text: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    val answer: String? = null,
) {
    val isAnswered: Boolean get() = !answer.isNullOrBlank()
}

/** A blocking question the agent asked and is waiting on. */
data class InteractiveRequest(
    val requestId: String,
    val kind: EntryKind,
    val title: String,
    val detail: String,
    /**
     * Allowed answers, straight from the server — for approvals the vocabulary is
     * `once` / `session` / `always` / `deny`, and `always` is simply absent when
     * the policy forbids a permanent allow.
     */
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
    /** Set to the chosen answer once the user responds. */
    val answer: String? = null,
    /**
     * For a `clarify.request` in its batch form: every question separately,
     * each answered on its own. Empty for the single-answer kinds.
     */
    val questions: List<ClarifyQuestion> = emptyList(),
    /**
     * The request stopped being answerable before anyone answered it — the
     * gateway fails an unanswered approval closed on a timeout, and the turn
     * ends without it. An unanswered card would otherwise sit there claiming to
     * be "Waiting" for something that no longer exists.
     */
    val expired: Boolean = false,
) {
    /**
     * Still answerable. A batch stays pending until every question has an answer,
     * and only the last one releases the agent.
     */
    val isPending: Boolean
        get() = !expired && when {
            questions.isNotEmpty() -> questions.any { !it.isAnswered }
            else -> answer == null
        }

    /** How many questions of a batch are still unanswered. */
    val unansweredCount: Int get() = questions.count { !it.isAnswered }
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
