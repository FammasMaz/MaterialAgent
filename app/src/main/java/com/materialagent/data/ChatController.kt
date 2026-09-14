package com.materialagent.data

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

        val result = connection.send(
            "prompt.submit",
            buildJsonObject {
                put("session_id", JsonPrimitive(liveId))
                put("text", JsonPrimitive(text))
            },
        )
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
        val sessionId = _transcript.value.sessionId ?: return Result.success(Unit)
        val result = sessions.interrupt(sessionId)
        if (result.isSuccess) {
            _transcript.value = _transcript.value.copy(running = false, turnStartedAt = null)
            _cues.tryEmit(HapticCue.INTERRUPTED)
        }
        return result
    }

    suspend fun steer(text: String): Result<Unit> {
        val sessionId = _transcript.value.sessionId ?: return Result.success(Unit)
        return sessions.steer(sessionId, text)
    }

    suspend fun approve(requestId: String, decision: String, permanent: Boolean = false): Result<Unit> =
        respond("approval.respond", requestId, decision, permanent)

    suspend fun answerClarification(requestId: String, answer: String): Result<Unit> =
        respond("clarify.respond", requestId, answer)

    suspend fun answerSudo(requestId: String, password: String): Result<Unit> =
        respond("sudo.respond", requestId, password)

    suspend fun answerSecret(requestId: String, value: String): Result<Unit> =
        respond("secret.respond", requestId, value)

    private suspend fun respond(
        method: String,
        requestId: String,
        value: String,
        permanent: Boolean = false,
    ): Result<Unit> {
        val sessionId = _transcript.value.sessionId
        val params = buildJsonObject {
            put("request_id", JsonPrimitive(requestId))
            put("response", JsonPrimitive(value))
            put("value", JsonPrimitive(value))
            if (permanent) put("allow_permanent", JsonPrimitive(true))
            if (sessionId != null) put("session_id", JsonPrimitive(sessionId))
        }
        val result = connection.send(method, params)
        _transcript.value = result.fold(
            onSuccess = { ChatReducer.markInteractionAnswered(_transcript.value, requestId, value) },
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

    suspend fun setModel(model: String): Result<Unit> {
        val sessionId = _transcript.value.sessionId ?: return Result.success(Unit)
        return sessions.setModel(sessionId, model)
    }

    suspend fun setReasoning(effort: String): Result<Unit> {
        val sessionId = _transcript.value.sessionId ?: return Result.success(Unit)
        return sessions.setReasoning(sessionId, effort)
    }

    suspend fun setFast(enabled: Boolean): Result<Unit> {
        val sessionId = _transcript.value.sessionId ?: return Result.success(Unit)
        return sessions.setFast(sessionId, enabled)
    }

    /** True when the transcript has a blocking question waiting for the user. */
    fun needsAttention(): Boolean = _transcript.value.pendingInteractions.isNotEmpty()

    fun markInteractionLocally(requestId: String, answer: String) {
        _transcript.value = ChatReducer.markInteractionAnswered(_transcript.value, requestId, answer)
    }

    fun kindFor(requestId: String): EntryKind? =
        _transcript.value.entries.firstOrNull { it.interactive?.requestId == requestId }?.kind

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
