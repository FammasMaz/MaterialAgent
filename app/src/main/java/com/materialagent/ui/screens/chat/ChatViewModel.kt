package com.materialagent.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val storedSessionId: String?,
    private val title: String = "",
) : ViewModel() {

    val transcript: StateFlow<ChatTranscript> = container.chat.transcript
    val sending: StateFlow<Boolean> = container.chat.sending

    /** Models the server offers, for the in-chat model picker. */
    val providers = container.capabilities.providers

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _configBusy = MutableStateFlow(false)
    val configBusy: StateFlow<Boolean> = _configBusy.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    init {
        viewModelScope.launch {
            val alreadyOpen = container.chat.transcript.value.sessionId
            // Re-entering the same conversation (e.g. after rotation) must not
            // re-resume and throw away the live turn.
            if (alreadyOpen != null &&
                container.chat.transcript.value.storedSessionId == storedSessionId
            ) {
                return@launch
            }
            if (storedSessionId != null) {
                container.chat.resume(storedSessionId, titleHint = title).onFailure { error ->
                    _notice.value = error.message ?: "Could not open that conversation"
                }
            }
        }
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        _draft.value = ""
        viewModelScope.launch {
            container.chat.submit(text).onFailure { error ->
                _notice.value = error.message ?: "Message not delivered"
                // Put the text back so nothing the user typed is ever lost.
                _draft.value = text
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

    fun answer(request: InteractiveRequest, value: String, permanent: Boolean = false) {
        viewModelScope.launch {
            val result = when (request.kind) {
                com.materialagent.data.chat.EntryKind.APPROVAL ->
                    container.chat.approve(request.requestId, value, permanent)
                com.materialagent.data.chat.EntryKind.CLARIFY ->
                    container.chat.answerClarification(request.requestId, value)
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
        val id = transcript.value.storedSessionId ?: return
        viewModelScope.launch {
            container.sessions.branch(id).fold(
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
