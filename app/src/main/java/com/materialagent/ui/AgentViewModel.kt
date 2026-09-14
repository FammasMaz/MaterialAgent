package com.materialagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.materialagent.MaterialAgentApp
import com.materialagent.data.AppContainer
import com.materialagent.data.AppSettings
import com.materialagent.data.ChatController
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.data.ServerProfile
import com.materialagent.ui.theme.LocalHapticLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The object graph, reached from the Application singleton. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as MaterialAgentApp).container }
}

/**
 * Builds a screen ViewModel from the app container.
 *
 * Screens never construct their own dependencies, and the container is never
 * passed into composables deeper than this — feature code sees only its own VM.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = rememberContainer()
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}

/**
 * App-level state: who we are connected to, how the app should look, and the
 * server's own identity once we've shaken hands with it.
 */
class AgentViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<ServerProfile>> = container.settings.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val status: StateFlow<ConnectionStatus> = container.connection.status
    val skin = container.connection.skin
    val serverVersion = container.connection.client.serverVersion

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** True once preferences have been read, so routing can wait instead of guessing. */
    private val _profilesLoaded = MutableStateFlow(false)
    val profilesLoaded: StateFlow<Boolean> = _profilesLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.profiles.first()
            _profilesLoaded.value = true
        }
    }

    private val _lastProblem = MutableStateFlow<String?>(null)
    val lastProblem: StateFlow<String?> = _lastProblem.asStateFlow()

    /** Saves (or updates) a server and immediately tries to connect to it. */
    fun saveAndConnect(profile: ServerProfile, secret: String) {
        viewModelScope.launch {
            _busy.value = true
            _lastProblem.value = null
            if (secret.isNotBlank()) container.secrets.put(profile.id, secret)
            container.settings.upsertProfile(profile)
            container.settings.setActiveProfile(profile.id)
            when (val result = container.connection.activate(profile)) {
                is ConnectionStatus.Failed -> _lastProblem.value = result.message
                else -> {
                    container.settings.markConnected(profile.id, System.currentTimeMillis())
                    container.sessions.refresh()
                }
            }
            _busy.value = false
        }
    }

    fun connect(profile: ServerProfile) {
        viewModelScope.launch {
            _busy.value = true
            _lastProblem.value = null
            container.settings.setActiveProfile(profile.id)
            when (val result = container.connection.activate(profile)) {
                is ConnectionStatus.Failed -> _lastProblem.value = result.message
                else -> {
                    container.settings.markConnected(profile.id, System.currentTimeMillis())
                    container.sessions.refresh()
                }
            }
            _busy.value = false
        }
    }

    fun retry() {
        container.connection.retry()
    }

    fun disconnect() = container.connection.deactivate()

    fun forget(profile: ServerProfile) {
        viewModelScope.launch {
            container.secrets.remove(profile.id)
            container.settings.removeProfile(profile.id)
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun dismissProblem() {
        _lastProblem.value = null
    }

    /** Secret already stored for a profile, so the edit form can show a filled state. */
    fun storedSecret(profileId: String): String? = container.secrets.get(profileId)

    val chat: ChatController get() = container.chat
    val haptics get() = container.haptics
}


/**
 * The one-liner composables use to fire a semantic haptic.
 *
 * Call sites say *what happened* ("a tool finished"), not which vibration
 * pattern to play — the cue-to-pattern mapping and the user's intensity
 * preference are resolved in one place.
 */
@Composable
fun rememberCue(): (HapticCue) -> Unit {
    val container = rememberContainer()
    val level = LocalHapticLevel.current
    return remember(container, level) {
        { cue: HapticCue -> container.haptics.perform(cue, level) }
    }
}
