package com.materialagent.data

import com.materialagent.core.arr
import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.HistoryRow
import com.materialagent.core.model.SessionInfo
import com.materialagent.core.model.SessionSummary
import com.materialagent.core.obj
import com.materialagent.core.objOrNull
import com.materialagent.core.str
import com.materialagent.core.strOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A freshly created runtime session. */
data class CreatedSession(
    val sessionId: String,
    val storedSessionId: String?,
    val info: SessionInfo?,
)

/** A resumed durable session, with its transcript. */
data class ResumedSession(
    val sessionId: String,
    val storedSessionId: String?,
    val title: String,
    val messages: List<HistoryRow>,
    val info: SessionInfo?,
    /** An approval that was still waiting when the session was reopened. */
    val pendingApproval: JsonObject? = null,
    /** A question that was still waiting when the session was reopened. */
    val pendingClarify: JsonObject? = null,
)

/**
 * Session catalogue and lifecycle.
 *
 * The server is the source of truth: this repository caches the list for the UI
 * and invalidates it whenever the gateway emits `sessions.changed`, which fires
 * on create, rename, delete, title-generation and turn completion. The event is
 * bursty (several per turn), so refreshes are debounced.
 */
@OptIn(FlowPreview::class)
class SessionRepository(
    private val connection: HermesConnection,
    private val scope: CoroutineScope,
) {

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var watcher: Job? = null
    private var statusWatcher: Job? = null

    /** Starts reacting to server-side list changes. Safe to call repeatedly. */
    fun start() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            connection.events
                .filter { it.type == GatewayEvent.SESSIONS_CHANGED || it.type == GatewayEvent.SESSION_RECLAIMED }
                .debounce(700)
                .collect { refresh() }
        }
        // Reload the inbox whenever the connection comes up.
        //
        // Without this, a refresh issued while the socket is still dialling fails
        // with "Gateway not connected" — and nothing tried again. Launch, Retry and
        // returning from the background all end the same way: an empty list under a
        // connection error, which reads as "the connection keeps dropping" even
        // though the socket is fine a moment later. The list is cheap to reload and
        // is the app's whole front page, so it reloads itself.
        statusWatcher = scope.launch {
            connection.status
                .filter { it is ConnectionStatus.Connected }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    suspend fun refresh(): Result<List<SessionSummary>> {
        _loading.value = true
        val result = connection.send("session.list")
        _loading.value = false
        result.fold(
            onSuccess = { payload ->
                val list = payload.arr("sessions")
                    ?.mapNotNull { item -> item.objOrNull()?.let(SessionSummary::from) }
                    .orEmpty()
                    .sortedByDescending { it.startedAt }
                _sessions.value = list
                _lastError.value = null
            },
            onFailure = { error ->
                // Only a failure that happened over a live socket is a *list* problem.
                // An error recorded while the socket is down is not: the disconnected
                // state already says what to do, and storing it here made the banner
                // outlive the outage — so a working connection kept showing "cancelled
                // before it finished" until the user tapped Retry.
                _lastError.value = if (connection.status.value is ConnectionStatus.Connected) {
                    error.message
                } else {
                    null
                }
            },
        )
        return result.map { _sessions.value }
    }

    suspend fun create(
        title: String? = null,
        model: String? = null,
        provider: String? = null,
        reasoningEffort: String? = null,
        cwd: String? = null,
        profile: String? = null,
    ): Result<CreatedSession> = connection.send(
        "session.create",
        buildJsonObject {
            put("source", JsonPrimitive("mobile"))
            title?.takeIf { it.isNotBlank() }?.let { put("title", JsonPrimitive(it)) }
            model?.takeIf { it.isNotBlank() }?.let { put("model", JsonPrimitive(it)) }
            provider?.takeIf { it.isNotBlank() }?.let { put("provider", JsonPrimitive(it)) }
            reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", JsonPrimitive(it)) }
            cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", JsonPrimitive(it)) }
            profile?.takeIf { it.isNotBlank() }?.let { put("profile", JsonPrimitive(it)) }
        },
    ).map { payload ->
        CreatedSession(
            sessionId = payload.str("session_id").orEmpty(),
            storedSessionId = payload.str("stored_session_id"),
            info = SessionInfo.from(payload.obj("info")),
        )
    }

    /**
     * Resumes a stored session. The gateway returns the durable transcript
     * inline, which is what the chat screen renders before any new turn.
     */
    suspend fun resume(storedId: String): Result<ResumedSession> = connection.send(
        "session.resume",
        buildJsonObject { put("session_id", JsonPrimitive(storedId)) },
        // Large transcripts legitimately take a while to rehydrate.
        timeoutMs = 90_000,
    ).map { payload ->
        ResumedSession(
            sessionId = payload.str("session_id").orEmpty(),
            storedSessionId = storedId,
            title = payload.str("title").orEmpty(),
            messages = payload.arr("messages")
                ?.mapNotNull { it.objOrNull()?.let(HistoryRow::from) }
                .orEmpty(),
            info = SessionInfo.from(payload.obj("info")),
            // The gateway replays what was still blocking the session, so a card
            // that arrived while the socket was down is not lost on reconnect.
            pendingApproval = payload.obj("pending_approval"),
            pendingClarify = payload.obj("pending_clarify"),
        )
    }

    suspend fun history(sessionId: String): Result<List<HistoryRow>> = connection.send(
        "session.history",
        buildJsonObject { put("session_id", JsonPrimitive(sessionId)) },
    ).map { payload ->
        payload.arr("messages")?.mapNotNull { it.objOrNull()?.let(HistoryRow::from) }.orEmpty()
    }

    suspend fun rename(sessionId: String, title: String): Result<Unit> = connection.send(
        "session.title",
        buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
            put("title", JsonPrimitive(title))
        },
    ).map { }

    /**
     * Branches a conversation that is open in the gateway right now.
     *
     * `session.branch` identifies its source by the *runtime* id, and answers
     * "session not found" (4001) for the stored id from `session.list`. There
     * are two ways to have a runtime id, so there are two functions rather than
     * one taking both — a single call with two same-typed nullable ids is too
     * easy to get backwards.
     */
    suspend fun branchOpenSession(runtimeSessionId: String): Result<CreatedSession> =
        branchRequest(runtimeSessionId)

    /**
     * Branches a conversation known only by the stored id from `session.list`,
     * opening it first to learn its runtime id. Note that `session.resume` does
     * not echo `stored_session_id`, so this is the only way a list row can
     * branch.
     */
    suspend fun branchStoredSession(storedSessionId: String): Result<CreatedSession> =
        resume(storedSessionId).fold(
            onSuccess = { branchRequest(it.sessionId) },
            onFailure = { Result.failure(it) },
        )

    private suspend fun branchRequest(runtimeSessionId: String): Result<CreatedSession> {
        return connection.send(
            "session.branch",
            buildJsonObject { put("session_id", JsonPrimitive(runtimeSessionId)) },
            timeoutMs = 60_000,
        ).map { payload ->
            CreatedSession(
                sessionId = payload.str("session_id").orEmpty(),
                storedSessionId = payload.str("stored_session_id"),
                info = SessionInfo.from(payload.obj("info")),
            )
        }
    }

    suspend fun close(sessionId: String): Result<Unit> = connection.send(
        "session.close",
        buildJsonObject { put("session_id", JsonPrimitive(sessionId)) },
    ).map { }

    /**
     * Deleting requires the runtime session to be closed first — the gateway
     * answers 4023 otherwise. Closing here keeps the caller from having to know
     * that rule.
     */
    suspend fun delete(runtimeSessionId: String?, storedSessionId: String): Result<Unit> {
        if (runtimeSessionId != null) close(runtimeSessionId)
        return connection.send(
            "session.delete",
            buildJsonObject { put("session_id", JsonPrimitive(storedSessionId)) },
        ).map { }.onSuccess {
            _sessions.value = _sessions.value.filterNot { it.id == storedSessionId }
        }
    }

    suspend fun interrupt(sessionId: String): Result<Unit> = connection.send(
        "session.interrupt",
        buildJsonObject { put("session_id", JsonPrimitive(sessionId)) },
    ).map { }

    suspend fun steer(sessionId: String, text: String): Result<Unit> = connection.send(
        "session.steer",
        buildJsonObject {
            put("session_id", JsonPrimitive(sessionId))
            put("text", JsonPrimitive(text))
        },
    ).map { }

    suspend fun setModel(sessionId: String, model: String): Result<Unit> = connection.send(
        "config.set",
        buildJsonObject {
            put("key", JsonPrimitive("model"))
            put("value", JsonPrimitive(model))
            put("session_id", JsonPrimitive(sessionId))
        },
    ).map { }

    suspend fun setReasoning(sessionId: String, effort: String): Result<Unit> = connection.send(
        "config.set",
        buildJsonObject {
            put("key", JsonPrimitive("reasoning"))
            put("value", JsonPrimitive(effort))
            put("session_id", JsonPrimitive(sessionId))
        },
    ).map { }

    suspend fun setFast(sessionId: String, enabled: Boolean): Result<Unit> = connection.send(
        "config.set",
        buildJsonObject {
            put("key", JsonPrimitive("fast"))
            put("value", JsonPrimitive(enabled))
            put("session_id", JsonPrimitive(sessionId))
        },
    ).map { }

    /** Human-readable status block; the server renders the text. */
    suspend fun status(sessionId: String): String? = connection.send(
        "session.status",
        buildJsonObject { put("session_id", JsonPrimitive(sessionId)) },
    ).getOrNull()?.str("output")

    suspend fun mostRecent(): Pair<String, String>? = connection.send("session.most_recent")
        .getOrNull()
        ?.let { payload ->
            val id = payload.str("session_id") ?: return@let null
            id to payload.str("title").orEmpty()
        }
}
