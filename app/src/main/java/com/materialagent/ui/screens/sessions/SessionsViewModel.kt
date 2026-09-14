package com.materialagent.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialagent.data.AppContainer
import com.materialagent.core.model.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the sessions list is currently filtered. */
enum class SessionFilter(val label: String) {
    ALL("All"),
    MINE("Conversations"),
    AUTOMATIONS("Automations"),
}

/**
 * Backs the sessions list.
 *
 * The list itself is server-owned and shared through [AppContainer.sessions], so
 * this class only adds the view concerns: the search query, the filter, and
 * a place to surface a failed rename/delete without hijacking the whole screen.
 */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    val loading = container.sessions.loading
    val error = container.sessions.lastError

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SessionFilter.ALL)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _busyId = MutableStateFlow<String?>(null)

    /**
     * Which groups the user has folded away, keyed by [SessionSummary.groupKey].
     *
     * It lives here, not in the row, because a LazyColumn disposes rows that
     * scroll off screen — state kept there would be forgotten on the way back up.
     */
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val collapsedGroups: StateFlow<Set<String>> = _collapsedGroups.asStateFlow()

    /** The stored session id currently being renamed/deleted, so the row can show progress. */
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    init {
        container.sessions.start()
        refresh()
    }

    val sessions: StateFlow<List<SessionSummary>> =
        combine(
            container.sessions.sessions,
            _query,
            _filter,
        ) { list, query, filter ->
            list.filter { session ->
                val matchesFilter = when (filter) {
                    SessionFilter.ALL -> true
                    SessionFilter.MINE -> !session.isAutomation
                    SessionFilter.AUTOMATIONS -> session.isAutomation
                }
                val matchesQuery = query.isBlank() ||
                    session.title.contains(query, ignoreCase = true) ||
                    session.preview.contains(query, ignoreCase = true)
                matchesFilter && matchesQuery
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun search(text: String) {
        _query.value = text
    }

    fun filterBy(filter: SessionFilter) {
        _filter.value = filter
    }

    fun toggleGroup(groupKey: String) {
        _collapsedGroups.update { if (groupKey in it) it - groupKey else it + groupKey }
    }

    fun refresh() {
        viewModelScope.launch { container.sessions.refresh() }
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            _busyId.value = id
            container.sessions.rename(id, title)
                .onFailure { _actionError.value = it.message ?: "Could not rename that session" }
            container.sessions.refresh()
            _busyId.value = null
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            _busyId.value = id
            val liveId = container.chat.transcript.value.storedSessionId?.takeIf { it == id }
                ?.let { container.chat.transcript.value.sessionId }
            container.sessions.delete(liveId, id)
                .onFailure { _actionError.value = it.message ?: "Could not delete that session" }
            _busyId.value = null
        }
    }

    /** Forks a session into a new one and reports the new id so the UI can open it. */
    fun branch(id: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busyId.value = id
            container.sessions.branchStoredSession(id).fold(
                onSuccess = { created ->
                    container.sessions.refresh()
                    onCreated(created.storedSessionId ?: created.sessionId)
                },
                onFailure = { _actionError.value = it.message ?: "Could not branch that session" },
            )
            _busyId.value = null
        }
    }

    fun dismissError() {
        _actionError.value = null
    }

    fun clearSearch() {
        _query.value = ""
    }
}
