package com.materialagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialagent.core.AuthMode
import com.materialagent.core.HermesJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "materialagent")

/** Persisted form of [ServerProfile] — kept separate from the UI model so the
 *  on-disk shape can migrate independently. */
@Serializable
private data class StoredProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val authMode: String,
    val username: String = "",
    val profileName: String? = null,
    val lastConnectedAt: Long = 0L,
)

/**
 * Everything the app remembers between launches.
 *
 * Servers live in a JSON list behind a single key — there are a handful of them
 * at most, and reading the whole list is cheaper than a schema for it. Credentials
 * are *not* here; they are in [SecretStore].
 */
class SettingsStore(context: Context) {

    private val store = context.dataStore

    val profiles: Flow<List<ServerProfile>> = store.data.map { prefs ->
        prefs[PROFILES].orEmpty().let { raw ->
            if (raw.isBlank()) emptyList()
            else runCatching {
                Json.decodeFromString(ListSerializer(StoredProfile.serializer()), raw).map { it.toModel() }
            }.getOrDefault(emptyList())
        }
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        // Fallbacks come from the model's own defaults, never from a second copy
        // of them here: a duplicated default silently wins over the real one.
        val d = AppSettings()
        AppSettings(
            themeMode = prefs[THEME_MODE].toEnum(d.themeMode),
            palette = prefs[PALETTE].toEnum(d.palette),
            motionLevel = prefs[MOTION].toEnum(d.motionLevel),
            hapticLevel = prefs[HAPTICS].toEnum(d.hapticLevel),
            showReasoning = prefs[SHOW_REASONING] ?: d.showReasoning,
            showToolCalls = prefs[SHOW_TOOLS] ?: d.showToolCalls,
            streamingHaptics = prefs[STREAMING_HAPTICS] ?: d.streamingHaptics,
            scrollHaptics = prefs[SCROLL_HAPTICS] ?: d.scrollHaptics,
            groupSessions = prefs[GROUP_SESSIONS] ?: d.groupSessions,
            sendOnEnter = prefs[SEND_ON_ENTER] ?: d.sendOnEnter,
            allowScreenshots = prefs[ALLOW_SCREENSHOTS] ?: d.allowScreenshots,
            autoCheckUpdates = prefs[AUTO_CHECK_UPDATES] ?: d.autoCheckUpdates,
            skippedVersion = prefs[SKIPPED_VERSION]?.takeIf { it.isNotBlank() } ?: d.skippedVersion,
            activeProfileId = prefs[ACTIVE_PROFILE] ?: d.activeProfileId,
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    /**
     * When the updater last asked GitHub.
     *
     * Dedicated accessors rather than fields on [AppSettings]: this is the
     * updater's own bookkeeping, never rendered and never edited by hand, so it
     * does not belong in the model the UI binds to.
     */
    suspend fun lastUpdateCheck(): Long = store.data.first()[LAST_UPDATE_CHECK] ?: 0L

    suspend fun markUpdateCheck(at: Long) {
        store.edit { prefs -> prefs[LAST_UPDATE_CHECK] = at }
    }

    suspend fun upsertProfile(profile: ServerProfile) {
        store.edit { prefs ->
            val current = decode(prefs[PROFILES])
            val next = current.filterNot { it.id == profile.id } + profile.toStored()
            prefs[PROFILES] = Json.encodeToString(ListSerializer(StoredProfile.serializer()), next)
            if (prefs[ACTIVE_PROFILE] == null) prefs[ACTIVE_PROFILE] = profile.id
        }
    }

    suspend fun removeProfile(id: String) {
        store.edit { prefs ->
            val next = decode(prefs[PROFILES]).filterNot { it.id == id }
            prefs[PROFILES] = Json.encodeToString(ListSerializer(StoredProfile.serializer()), next)
            if (prefs[ACTIVE_PROFILE] == id) {
                prefs[ACTIVE_PROFILE] = next.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActiveProfile(id: String?) {
        store.edit { prefs -> prefs[ACTIVE_PROFILE] = id.orEmpty() }
    }

    suspend fun markConnected(id: String, at: Long) {
        store.edit { prefs ->
            val next = decode(prefs[PROFILES]).map {
                if (it.id == id) it.copy(lastConnectedAt = at) else it
            }
            prefs[PROFILES] = Json.encodeToString(ListSerializer(StoredProfile.serializer()), next)
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = snapshot()
        val next = transform(current)
        store.edit { prefs ->
            prefs[THEME_MODE] = next.themeMode.name
            prefs[PALETTE] = next.palette.name
            prefs[MOTION] = next.motionLevel.name
            prefs[HAPTICS] = next.hapticLevel.name
            prefs[SHOW_REASONING] = next.showReasoning
            prefs[SHOW_TOOLS] = next.showToolCalls
            prefs[STREAMING_HAPTICS] = next.streamingHaptics
            prefs[SCROLL_HAPTICS] = next.scrollHaptics
            prefs[GROUP_SESSIONS] = next.groupSessions
            prefs[SEND_ON_ENTER] = next.sendOnEnter
            prefs[ALLOW_SCREENSHOTS] = next.allowScreenshots
            prefs[AUTO_CHECK_UPDATES] = next.autoCheckUpdates
            prefs[SKIPPED_VERSION] = next.skippedVersion.orEmpty()
            prefs[ACTIVE_PROFILE] = next.activeProfileId.orEmpty()
        }
    }

    private fun decode(raw: String?): List<StoredProfile> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { Json.decodeFromString(ListSerializer(StoredProfile.serializer()), raw) }
            .getOrDefault(emptyList())

    private fun StoredProfile.toModel() = ServerProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        authMode = runCatching { AuthMode.valueOf(authMode) }.getOrDefault(AuthMode.TOKEN),
        username = username,
        profileName = profileName,
        lastConnectedAt = lastConnectedAt,
    )

    private fun ServerProfile.toStored() = StoredProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        authMode = authMode.name,
        username = username,
        profileName = profileName,
        lastConnectedAt = lastConnectedAt,
    )

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        val PROFILES = stringPreferencesKey("profiles")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PALETTE = stringPreferencesKey("palette")
        val MOTION = stringPreferencesKey("motion_level")
        val HAPTICS = stringPreferencesKey("haptic_level")
        val SHOW_REASONING = booleanPreferencesKey("show_reasoning")
        val SHOW_TOOLS = booleanPreferencesKey("show_tool_calls")
        val STREAMING_HAPTICS = booleanPreferencesKey("streaming_haptics")
        val SCROLL_HAPTICS = booleanPreferencesKey("scroll_haptics")
        val GROUP_SESSIONS = booleanPreferencesKey("group_sessions")
        val SEND_ON_ENTER = booleanPreferencesKey("send_on_enter")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }
}
