package com.materialagent.data

import android.content.Context
import com.materialagent.core.HermesJson
import com.materialagent.data.update.GitHubReleaseApiClient
import com.materialagent.data.update.UpdateManager
import com.materialagent.ui.haptics.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The app's object graph, built once and held by the Application.
 *
 * A hand-rolled container, not a DI framework: there are seven objects, all
 * singletons, and the only thing a framework would buy here is annotation
 * processing. Everything takes its dependencies through the constructor, so the
 * transport and the reducers stay plain-JVM testable.
 */
class AppContainer(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val secrets = SecretStore(context)
    val haptics = Haptics(context)

    val connection = HermesConnection(Http.client, secrets, scope)
    val sessions = SessionRepository(connection, scope)
    val capabilities = CapabilityRepository(connection)
    val chat = ChatController(connection, sessions, scope)
    val updates = UpdateManager(
        context = context,
        settings = settings,
        api = GitHubReleaseApiClient(Http.client, HermesJson),
        client = Http.client,
        scope = scope,
    )

    /** Reconnects to the last-used server on launch, if there is one. */
    fun autoconnect() {
        scope.launch {
            sessions.start()
            val snapshot = settings.snapshot()
            val profiles = settings.profiles.first()
            val active = profiles.firstOrNull { it.id == snapshot.activeProfileId } ?: return@launch
            if (secrets.get(active.id).isNullOrBlank()) return@launch
            connection.activate(active)
        }
    }
}
