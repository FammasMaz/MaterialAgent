package com.materialagent.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialagent.core.model.OutgoingAttachment
import com.materialagent.core.model.OutgoingAttachments
import com.materialagent.data.AppContainer
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.data.chat.InteractiveRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs one conversation.
 *
 * The transcript itself lives in the container-wide `ChatController`, which keeps
 * folding events while this screen is off-screen; this class owns only the parts
 * that belong to the *view*: the draft, the sheet/expansion state, the composer's
 * inline error and the one-shot notices that should disappear on their own.
 */
class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val transcript: StateFlow<ChatTranscript> = container.chat.transcript
    val sending: StateFlow<Boolean> = container.chat.sending

    /** Models the server offers, for the in-chat model picker. */
    val providers = container.capabilities.providers

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /**
     * Files picked for the next turn, in the order they were added.
     *
     * View state, not transcript state: the files belong to the half-written
     * message in front of the user, exactly like the draft. The controller only
     * learns about them at send time, which is also when their bytes are read —
     * holding a 25 MB image in a screen's state for the minutes between picking
     * and sending would be the whole reason the app gets killed in the
     * background.
     */
    private val _attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())
    val attachments: StateFlow<List<OutgoingAttachment>> = _attachments.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _configBusy = MutableStateFlow(false)
    val configBusy: StateFlow<Boolean> = _configBusy.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /**
     * Opens [storedId] when the route's session argument points somewhere else
     * than what is on screen.
     *
     * This is driven from the screen's `storedId` rather than from `init`
     * because branching navigates to a sibling conversation at the same
     * destination: the navigation is single-top, so this screen's ViewModel is
     * reused and an init-time resume would never run again.
     */
    fun openIfNeeded(storedId: String?, title: String?) {
        if (storedId == null) return
        val current = container.chat.transcript.value
        // Already showing it — re-resuming would throw away a live turn.
        if (current.sessionId != null && current.storedSessionId == storedId) return
        viewModelScope.launch {
            container.chat.resume(storedId, titleHint = title).onFailure { error ->
                _notice.value = error.message ?: "Could not open that conversation"
            }
        }
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    /**
     * Adds a picked file, or explains why it cannot go.
     *
     * Size is checked here rather than at send time so the answer arrives while
     * the user is still looking at the picker's result. [OutgoingAttachment] can
     * be null because a picker that is cancelled still calls back.
     */
    fun attach(attachment: OutgoingAttachment?) {
        if (attachment == null) return
        OutgoingAttachments
            .rejectReason(attachment.kind, attachment.sizeBytes, attachment.name)
            ?.let { reason ->
                _notice.value = reason
                return
            }
        // Picking the same file twice is a slip, not a request to send it twice.
        if (_attachments.value.any { it.uri == attachment.uri }) return
        _attachments.value = _attachments.value + attachment
    }

    fun removeAttachment(attachment: OutgoingAttachment) {
        _attachments.value = _attachments.value.filterNot { it.uri == attachment.uri }
    }

    fun send() {
        val text = _draft.value.trim()
        val queued = _attachments.value
        // A picture with no words is still a message; only an empty composer with
        // nothing attached is a no-op.
        if (text.isEmpty() && queued.isEmpty()) return
        _draft.value = ""
        _attachments.value = emptyList()
        viewModelScope.launch {
            container.chat.submit(text, queued).onFailure { error ->
                _notice.value = error.message ?: "Message not delivered"
                // Put back everything the turn did not manage to send — the text
                // and the files alike. A failed upload the user cannot retry
                // without picking every photo again is a failure twice over.
                _draft.value = text
                _attachments.value = queued + _attachments.value
            }
        }
    }

    fun resend(text: String) {
        _draft.value = text
        send()
    }

    fun interrupt() {
        viewModelScope.launch { container.chat.interrupt() }
    }

    /** Injects guidance into a running turn without stopping it. */
    fun steer() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            container.chat.steer(text).onFailure { error ->
                _notice.value = error.message ?: "Steering failed"
                _draft.value = text
            }
        }
    }

    /**
     * Answers a blocking interaction.
     *
     * [questionId] carries the `qid` for a clarify batch — without it the gateway
     * accepts the answer and passes nothing to the agent.
     */
    fun answer(request: InteractiveRequest, value: String, questionId: String = "") {
        viewModelScope.launch {
            val result = when (request.kind) {
                com.materialagent.data.chat.EntryKind.APPROVAL ->
                    container.chat.approve(request.requestId, value)
                com.materialagent.data.chat.EntryKind.CLARIFY ->
                    container.chat.answerClarification(request.requestId, questionId, value)
                com.materialagent.data.chat.EntryKind.SUDO ->
                    container.chat.answerSudo(request.requestId, value)
                com.materialagent.data.chat.EntryKind.SECRET ->
                    container.chat.answerSecret(request.requestId, value)
                else -> Result.success(Unit)
            }
            result.onFailure { error ->
                _notice.value = error.message ?: "The agent did not accept that answer"
            }
        }
    }

    fun setModel(model: String) = configure { container.chat.setModel(model) }

    fun setReasoning(effort: String) = configure { container.chat.setReasoning(effort) }

    fun setFast(enabled: Boolean) = configure { container.chat.setFast(enabled) }

    fun branch(onCreated: (String) -> Unit) {
        // `session.branch` identifies an open session by its runtime id, which is
        // the only id this conversation reliably has — `session.resume` does not
        // return the stored one. Previously this required the stored id and so
        // returned silently without doing anything.
        val runtime = transcript.value.sessionId
        val stored = transcript.value.storedSessionId
        if (runtime == null && stored == null) return
        viewModelScope.launch {
            val result = if (runtime != null) {
                container.sessions.branchOpenSession(runtime)
            } else {
                container.sessions.branchStoredSession(stored!!)
            }
            result.fold(
                onSuccess = { created -> onCreated(created.storedSessionId ?: created.sessionId) },
                onFailure = { _notice.value = it.message ?: "Could not branch this conversation" },
            )
        }
    }

    /** Asks the server to render its own human-readable status block. */
    fun loadStatus() {
        val id = transcript.value.sessionId ?: return
        viewModelScope.launch {
            _statusText.value = container.sessions.status(id) ?: "No status available."
        }
    }

    fun clearStatus() {
        _statusText.value = null
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Pulls the model catalogue. Cheap, but not worth doing until it is asked for. */
    fun loadModelOptions() {
        viewModelScope.launch { container.capabilities.refreshModels() }
    }

    private fun configure(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _configBusy.value = true
            block().onFailure { error -> _notice.value = error.message ?: "The server rejected that change" }
            _configBusy.value = false
        }
    }
}
