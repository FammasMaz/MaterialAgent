package com.materialagent.ui.screens.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialagent.data.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the capabilities browser — what the agent on the other end can actually
 * do. Every list here is server-owned and re-read on demand; nothing is editable
 * except toolset enablement, because capability configuration belongs to the
 * server, not to a phone.
 */
class CapabilitiesViewModel(private val container: AppContainer) : ViewModel() {

    val providers = container.capabilities.providers
    val toolsets = container.capabilities.toolsets
    val skills = container.capabilities.skills
    val mcpServers = container.capabilities.mcpServers
    val error = container.capabilities.lastError

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            container.capabilities.refreshAll()
            _refreshing.value = false
        }
    }

    fun setToolsetEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            container.capabilities.setToolsetEnabled(name, enabled)
                .onFailure { _actionError.value = it.message ?: "Could not update $name" }
            container.capabilities.refreshTools()
        }
    }

    fun dismissError() {
        _actionError.value = null
    }
}
