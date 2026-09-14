package com.materialagent.data

import com.materialagent.core.HermesRpcException
import com.materialagent.core.InteractionParams
import com.materialagent.core.str
import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.SessionInfo
import com.materialagent.data.chat.ChatReducer
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.data.chat.EntryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Semantic moments the UI should feel — not a haptic call, because this layer
 * must stay Android-free. The Compose layer maps these to the haptics engine.
 */
enum class HapticCue {
    TURN_START,
    STREAM_TICK,
    TOOL_START,
    TOOL_DONE,
    NEEDS_ATTENTION,
    TURN_COMPLETE,
    TURN_FAILED,
    INTERRUPTED,
    SENT,
}

/**
 * Drives one live conversation.
 *
 * Owns the gateway session id, folds events through [ChatReducer], and turns
 * user intent into RPCs. Everything the chat screen needs is in [transcript].
 */
class ChatController(
    private val connection: HermesConnection,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
) {

    private val _transcript = MutableStateFlow(ChatTranscript())
    val transcript: StateFlow<ChatTranscript> = _transcript.asStateFlow()

    private val _cues = MutableSharedFlow<HapticCue>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val cues: SharedFlow<HapticCue> = _cues.asSharedFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var eventJob: Job? = null
    private var streamTicks = 0

    /** Attaches to a runtime session that already exists on the gateway. */
    fun attach(sessionId: String, storedSessionId: String?, title: String, info: SessionInfo?) {
        detach()
        _transcript.value = ChatTranscript(
            sessionId = sessionId,
            storedSessionId = storedSessionId,
            title = title,
            info = info,
            running = info?.running ?: false,
        )
        observe(sessionId)
    }

    /**
     * Resumes a durable session by its stored id.
     *
     * [titleHint] is what the caller already knows — the row the user tapped.
     * The resume payload carries the title under `info`, not at the top level,
     * so without this fallback a reopened conversation reads "Conversation".
     */
    suspend fun resume(storedId: String, titleHint: String? = null): Result<Unit> {
        detach()
        _transcript.value = ChatTranscript(loadingHistory = true)
        return sessions.resume(storedId).fold(
            onSuccess = { resumed ->
                _transcript.value = ChatReducer.fromHistory(
                    state = ChatTranscript(),
                    rows = resumed.messages,
                    sessionId = resumed.sessionId,
                    storedSessionId = resumed.storedSessionId ?: storedId,
                    title = resumed.title.ifBlank {
                        resumed.info?.title?.takeIf { it.isNotBlank() }
                            ?: titleHint.orEmpty()
                    },
                    info = resumed.info,
                    pendingApproval = resumed.pendingApproval,
                    pendingClarify = resumed.pendingClarify,
                )
                observe(resumed.sessionId)
                Result.success(Unit)
            },
            onFailure = { error ->
                _transcript.value = ChatTranscript(
                    loadingHistory = false,
                    historyError = error.message ?: "Could not load this session",
                )
                Result.failure(error)
            },
        )
    }

    /** Creates a brand-new session and attaches to it. */
    suspend fun create(
        title: String? = null,
        model: String? = null,
        provider: String? = null,
        reasoningEffort: String? = null,
        profile: String? = null,
    ): Result<Unit> = sessions.create(
        title = title,
        model = model,
        provider = provider,
        reasoningEffort = reasoningEffort,
        profile = profile,
    ).fold(
        onSuccess = { created ->
            attach(created.sessionId, created.storedSessionId, title.orEmpty(), created.info)
            Result.success(Unit)
        },
        onFailure = { error ->
            _transcript.value = ChatTranscript(historyError = error.message ?: "Could not start a session")
            Result.failure(error)
        },
    )

    fun detach() {
        eventJob?.cancel()
        eventJob = null
        streamTicks = 0
    }

    private fun observe(sessionId: String) {
        eventJob?.cancel()
        eventJob = scope.launch {
            connection.events.collect { event ->
                val sid = event.sessionId
                // Session-scoped events for other sessions are ignored; global
                // events (no session id) still matter (usage, list changes).
                if (sid != null && sid != sessionId) return@collect
                apply(event)
            }
        }
    }

    private fun apply(event: GatewayEvent) {
        val before = _transcript.value
        val after = ChatReducer.reduce(before, event, nowSeconds())
        _transcript.value = after
        emitCueFor(event, before, after)
    }

    private fun emitCueFor(event: GatewayEvent, before: ChatTranscript, after: ChatTranscript) {
        when (event.type) {
            GatewayEvent.MESSAGE_START -> _cues.tryEmit(HapticCue.TURN_START)

            GatewayEvent.MESSAGE_DELTA -> {
                // A 200-token burst must not become 200 buzzes; tick sparingly.
                streamTicks += 1
                if (streamTicks % 24 == 0) _cues.tryEmit(HapticCue.STREAM_TICK)
            }

            GatewayEvent.TOOL_START -> _cues.tryEmit(HapticCue.TOOL_START)

            GatewayEvent.TOOL_COMPLETE -> _cues.tryEmit(HapticCue.TOOL_DONE)

            GatewayEvent.APPROVAL_REQUEST,
            GatewayEvent.CLARIFY_REQUEST,
            GatewayEvent.SUDO_REQUEST,
            GatewayEvent.SECRET_REQUEST,
            -> _cues.tryEmit(HapticCue.NEEDS_ATTENTION)

            GatewayEvent.MESSAGE_COMPLETE -> {
                streamTicks = 0
                _cues.tryEmit(if (event.status == "error") HapticCue.TURN_FAILED else HapticCue.TURN_COMPLETE)
            }

            GatewayEvent.TURN_ERROR -> _cues.tryEmit(HapticCue.TURN_FAILED)

            else -> {
                if (before.running && !after.running) _cues.tryEmit(HapticCue.TURN_COMPLETE)
            }
        }
    }

    // ── User intent ─────────────────────────────────────────────────────────

    /**
     * Runs a session-scoped call against a runtime id that the gateway may have
     * re-minted while the app was away.
     *
     * The runtime id from `session.create`/`session.resume` is not durable across
     * a reconnect: after the socket drops and comes back, the gateway answers
     * `4001 session not found` for the id the transcript is still holding, and
     * every session-scoped method fails the same way — sending a turn, steering,
     * stopping, answering an approval, switching model. The durable id is not
     * affected, so the fix is to resume by it, adopt the new runtime id, and try
     * once more. Only a "session is gone" failure is retried; anything else is
     * reported as-is.
     */
    private suspend fun <T> withLiveSession(
        block: suspend (String) -> Result<T>,
    ): Result<T> {
        val current = _transcript.value.sessionId
            ?: return Result.failure(IllegalStateException("No open session"))
        val stored = _transcript.value.storedSessionId
        val first = block(current)
        val error = first.exceptionOrNull()
        if (stored == null || error !is HermesRpcException || !error.isSessionGone) return first
        val resumed = sessions.resume(stored).getOrElse { return first }
        _transcript.value = _transcript.value.copy(sessionId = resumed.sessionId)
        observe(resumed.sessionId)
        return block(resumed.sessionId)
    }

    suspend fun submit(text: String): Result<Unit> {
        val sessionId = _transcript.value.sessionId
        if (text.isBlank()) return Result.success(Unit)
        if (sessionId == null) {
            val created = create()
            if (created.isFailure) return created
        }
        val liveId = _transcript.value.sessionId ?: return Result.failure(IllegalStateException("No session"))
        val optimistic = ChatReducer.submitUser(_transcript.value, text, nowSeconds())
        _transcript.value = optimistic
        _sending.value = true
        _cues.tryEmit(HapticCue.SENT)

        val result = withLiveSession { runtimeId ->
            connection.send(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", JsonPrimitive(runtimeId))
                    put("text", JsonPrimitive(text))
                },
            )
        }
        _sending.value = false
        if (result.isFailure) {
            _transcript.value = _transcript.value.copy(
                running = false,
                historyError = result.exceptionOrNull()?.message ?: "Could not send",
            )
        }
        return result.map { }
    }

    suspend fun interrupt(): Result<Unit> {
        if (_transcript.value.sessionId == null) return Result.success(Unit)
        val result = withLiveSession { sessions.interrupt(it) }
        if (result.isSuccess) {
            _transcript.value = _transcript.value.copy(running = false, turnStartedAt = null)
            _cues.tryEmit(HapticCue.INTERRUPTED)
        }
        return result
    }

    suspend fun steer(text: String): Result<Unit> = withLiveSession { sessions.steer(it, text) }

    /**
     * Answers a blocking interaction. The parameter shapes live in
     * [InteractionParams] with the rest of the wire contract.
     */
    suspend fun approve(requestId: String, choice: String): Result<Unit> =
        respond("approval.respond", requestId, choice) { InteractionParams.approval(it, requestId, choice) }

    /**
     * Answers one question of a clarify request.
     *
     * [questionId] is the question's `qid`. It is not optional in practice: the
     * gateway resolves a batch per question and only releases the agent once the
     * last one is answered, while an answer without a `question_id` is accepted
     * with `status: ok` and delivered to nobody.
     *
     * The reply is inspected rather than assumed. `status: "expired"` means the
     * request is no longer outstanding — answered elsewhere, or past the
     * server-side deadline — and the card has to stop claiming otherwise.
     */
    suspend fun answerClarification(
        requestId: String,
        questionId: String,
        answer: String,
    ): Result<Unit> {
        if (_transcript.value.sessionId == null) {
            return Result.failure(IllegalStateException("No open session to answer in"))
        }
        return withLiveSession { connection.send("clarify.respond", InteractionParams.clarify(
                requestId = requestId,
                questionId = questionId,
                answer = answer,
            )) }
            .fold(
                onSuccess = { reply ->
                    val status = reply.str("status")
                    _transcript.value = when {
                        status == "expired" -> ChatReducer.markInteractionExpired(_transcript.value, requestId)
                        questionId.isNotEmpty() -> ChatReducer.markQuestionAnswered(
                            _transcript.value,
                            requestId,
                            questionId,
                            answer,
                        )

                        else -> ChatReducer.markInteractionAnswered(_transcript.value, requestId, answer)
                    }
                    Result.success(Unit)
                },
                onFailure = { error ->
                    _transcript.value = ChatReducer.failInteraction(
                        _transcript.value,
                        requestId,
                        error.message ?: "Could not send the answer",
                    )
                    Result.failure(error)
                },
            )
    }

    suspend fun answerSudo(requestId: String, password: String): Result<Unit> =
        respond("sudo.respond", requestId, password) { InteractionParams.sudo(requestId, password) }

    suspend fun answerSecret(requestId: String, value: String): Result<Unit> =
        respond("secret.respond", requestId, value) { InteractionParams.secret(requestId, value) }

    private suspend fun respond(
        method: String,
        requestId: String,
        uiValue: String,
        build: (String) -> JsonObject,
    ): Result<Unit> {
        if (_transcript.value.sessionId == null) {
            return Result.failure(IllegalStateException("No open session to answer in"))
        }
        val result = withLiveSession { runtimeId -> connection.send(method, build(runtimeId)) }
        _transcript.value = result.fold(
            onSuccess = { ChatReducer.markInteractionAnswered(_transcript.value, requestId, uiValue) },
            onFailure = { error ->
                ChatReducer.failInteraction(
                    _transcript.value,
                    requestId,
                    error.message ?: "Could not send the answer",
                )
            },
        )
        return result.map { }
    }

    suspend fun setModel(model: String): Result<Unit> = withLiveSession { sessions.setModel(it, model) }

    suspend fun setReasoning(effort: String): Result<Unit> =
        withLiveSession { sessions.setReasoning(it, effort) }

    suspend fun setFast(enabled: Boolean): Result<Unit> =
        withLiveSession { sessions.setFast(it, enabled) }

    /** True when the transcript has a blocking question waiting for the user. */
    fun needsAttention(): Boolean = _transcript.value.pendingInteractions.isNotEmpty()

    fun markInteractionLocally(requestId: String, answer: String) {
        _transcript.value = ChatReducer.markInteractionAnswered(_transcript.value, requestId, answer)
    }

    fun kindFor(requestId: String): EntryKind? =
        _transcript.value.entries.firstOrNull { it.interactive?.requestId == requestId }?.kind

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
