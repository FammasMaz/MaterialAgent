package com.materialagent.data

import com.materialagent.core.AuthMode
import com.materialagent.core.HermesClient
import com.materialagent.core.HermesJson
import com.materialagent.core.HermesTransportException
import com.materialagent.core.HermesUrl
import com.materialagent.core.arrOrNull
import com.materialagent.core.objOrNull
import com.materialagent.core.str
import com.materialagent.core.model.Skin
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** User-visible connection lifecycle. */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data class Connecting(val profile: ServerProfile) : ConnectionStatus
    data class Connected(val profile: ServerProfile) : ConnectionStatus
    data class Reconnecting(val profile: ServerProfile, val attempt: Int) : ConnectionStatus
    data class Failed(val profile: ServerProfile?, val message: String, val needsCredentials: Boolean) : ConnectionStatus

    val isBusy: Boolean get() = this is Connecting || this is Reconnecting
    val isConnected: Boolean get() = this is Connected
}

/**
 * Owns the socket lifecycle: credential resolution, dialling, retry policy and
 * reconnection. Feature code talks to [client] for RPC and to [status] for UI.
 *
 * Retry policy is exponential with jitter and a ceiling of ~30 s, and it stops
 * entirely on credential failures (4401/4403) — retrying a rejected password
 * forever is worse than telling the user.
 */
class HermesConnection(
    private val http: okhttp3.OkHttpClient,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
) {

    val client = HermesClient(http, scope)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    val skin: StateFlow<Skin?> get() = client.skin

    /** Flips true once the first `gateway.ready` lands; used to gate the UI. */
    private val _handshakeComplete = MutableStateFlow(false)
    val handshakeComplete: StateFlow<Boolean> = _handshakeComplete.asStateFlow()

    val events = client.events

    private var activeProfile: ServerProfile? = null
    private var reconnectJob: Job? = null
    @Volatile private var intentionalStop = false

    suspend fun activate(profile: ServerProfile): ConnectionStatus {
        intentionalStop = false
        stopWatcher()
        activeProfile = profile
        _status.value = ConnectionStatus.Connecting(profile)
        return runCatching { dial(profile) }
            .fold(
                onSuccess = {
                    val connected = ConnectionStatus.Connected(profile)
                    _status.value = connected
                    startWatcher(profile)
                    connected
                },
                onFailure = { error ->
                    val failure = classify(profile, error)
                    _status.value = failure
                    failure
                },
            )
    }

    fun deactivate() {
        intentionalStop = true
        stopWatcher()
        activeProfile = null
        _handshakeComplete.value = false
        client.disconnect("user disconnected")
        _status.value = ConnectionStatus.Idle
    }

    /** Re-dials the active profile; used by the "Retry" affordance. */
    fun retry() {
        val profile = activeProfile ?: return
        intentionalStop = false
        stopWatcher()
        reconnectJob = scope.launch {
            _status.value = ConnectionStatus.Connecting(profile)
            runCatching { dial(profile) }.fold(
                onSuccess = {
                    _status.value = ConnectionStatus.Connected(profile)
                    startWatcher(profile)
                },
                onFailure = { _status.value = classify(profile, it) },
            )
        }
    }

    /**
     * Opens the socket and completes the handshake. Deliberately does *not* start
     * the drop watcher: [startWatcher] owns that job, and a redial from inside the
     * watcher must not cancel the watcher that is running it.
     */
    private suspend fun dial(profile: ServerProfile) {
        val authParam = resolveAuth(profile)
        val wsUrl = HermesUrl.wsUrl(profile.baseUrl, authParam, profile.profileName)
            ?: throw HermesTransportException("Not a valid server address: ${profile.baseUrl}")

        client.connect(wsUrl)
        _handshakeComplete.value = true
    }

    private fun stopWatcher() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun startWatcher(profile: ServerProfile) {
        stopWatcher()
        reconnectJob = scope.launch {
            var attempt = 0
            while (!intentionalStop && activeProfile?.id == profile.id) {
                delay(WATCH_TICK_MS)
                val state = client.state.value
                if (state == com.materialagent.core.ConnectionState.ERROR) {
                    // Credential rejection or a hard socket error: surface it.
                    val message = if (_handshakeComplete.value) {
                        "Connection lost"
                    } else {
                        "Could not reach ${profile.baseUrl}"
                    }
                    _status.value = ConnectionStatus.Failed(profile, message, needsCredentials = false)
                    return@launch
                }
                if (state != com.materialagent.core.ConnectionState.OPEN) {
                    attempt += 1
                    _status.value = ConnectionStatus.Reconnecting(profile, attempt)
                    val backoff = (BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                        .coerceAtMost(MAX_BACKOFF_MS)
                    delay(backoff)
                    if (intentionalStop || activeProfile?.id != profile.id) return@launch
                    val ok = runCatching { dial(profile) }.isSuccess
                    if (ok) {
                        attempt = 0
                        _status.value = ConnectionStatus.Connected(profile)
                    }
                } else if (attempt != 0) {
                    attempt = 0
                    _status.value = ConnectionStatus.Connected(profile)
                }
            }
        }
    }

    /**
     * Resolves the credential the socket upgrade needs.
     *
     *  * [AuthMode.TOKEN]: the value the user pasted — compared in constant
     *    time by the server against `HERMES_DASHBOARD_SESSION_TOKEN`.
     *  * [AuthMode.PASSWORD]: log in over HTTP (which mints a session cookie),
     *    then exchange that cookie for a single-use 30 s WebSocket ticket.
     */
    private fun resolveAuth(profile: ServerProfile): Pair<String, String> {
        val secret = secrets.get(profile.id).orEmpty()
        return when (profile.authMode) {
            AuthMode.TOKEN -> {
                if (secret.isBlank()) {
                    throw HermesTransportException(
                        "No access token stored for ${profile.name}. Add one, or use password sign-in.",
                    )
                }
                "token" to secret
            }

            AuthMode.PASSWORD -> {
                if (secret.isBlank()) {
                    throw HermesTransportException("No password stored for ${profile.name}.")
                }
                loginForTicket(profile, secret)
            }
        }
    }

    private fun loginForTicket(profile: ServerProfile, password: String): Pair<String, String> {
        val providerName = discoverPasswordProvider(profile)
            ?: throw HermesTransportException(
                "This server has no password sign-in enabled. Start it with a password provider, or use a token over a tunnel.",
            )

        val loginUrl = HermesUrl.endpoint(profile.baseUrl, "/auth/password-login")
            ?: throw HermesTransportException("Invalid server address: ${profile.baseUrl}")
        val body = buildJsonObject {
            put("provider", JsonPrimitive(providerName))
            put("username", JsonPrimitive(profile.username.ifBlank { "default" }))
            put("password", JsonPrimitive(password))
            put("next", JsonPrimitive("/"))
        }
        val loginRequest = Request.Builder()
            .url(loginUrl)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(loginRequest).execute().use { response ->
            when (response.code) {
                in 200..299 -> Unit
                401 -> throw HermesTransportException("Wrong username or password for ${profile.name}.")
                429 -> throw HermesTransportException("Too many attempts. Wait a minute and try again.")
                else -> throw HermesTransportException("Sign-in failed (HTTP ${response.code}).")
            }
        }

        val ticketUrl = HermesUrl.endpoint(profile.baseUrl, "/api/auth/ws-ticket")
            ?: throw HermesTransportException("Invalid server address: ${profile.baseUrl}")
        val ticketRequest = Request.Builder().url(ticketUrl).post(EMPTY_BODY).build()
        val payload = http.newCall(ticketRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw HermesTransportException(
                    if (response.code == 401) {
                        "Sign-in expired. Try again."
                    } else {
                        "Could not mint a WebSocket ticket (HTTP ${response.code})."
                    },
                )
            }
            val text = response.body?.string().orEmpty()
            runCatching { HermesJson.parseToJsonElement(text).objOrNull() }.getOrNull()
        }
        val ticket = payload.str("ticket")
            ?: throw HermesTransportException("The server did not return a WebSocket ticket.")
        return "ticket" to ticket
    }

    /** Finds a session provider that accepts a password, if the server has one. */
    private fun discoverPasswordProvider(profile: ServerProfile): String? {
        val url = HermesUrl.endpoint(profile.baseUrl, "/api/auth/providers")
            ?: return null
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = HermesJson.parseToJsonElement(response.body?.string().orEmpty()).objOrNull()
                val providers = root?.get("providers")?.arrOrNull() ?: return@use null
                providers.mapNotNull { it.objOrNull() }
                    .firstOrNull { it.str("supports_password")?.let { flag -> flag == "true" || flag == "True" } == true }
                    ?.str("name")
            }
        }.getOrNull()
    }

    private fun classify(profile: ServerProfile, error: Throwable): ConnectionStatus.Failed {
        // Order matters: these are all IOExceptions, and the specific ones say
        // something far more useful than "could not reach".
        val message = when (error) {
            is UnknownServiceException ->
                "Android blocked plain HTTP to ${profile.baseUrl}. The network security " +
                    "configuration must permit cleartext for this build."

            is SocketTimeoutException ->
                "${profile.baseUrl} did not answer in time. Is the host awake and the " +
                    "port reachable from this network?"

            is ConnectException ->
                "Nothing is listening at ${profile.baseUrl}. Start `hermes serve` there, " +
                    "or check the port and any tunnel."

            is IOException -> "Could not reach ${profile.baseUrl}. Is `hermes serve` running?"
            else -> error.message ?: "Connection failed"
        }
        val needsCredentials = message.contains("token", ignoreCase = true) ||
            message.contains("password", ignoreCase = true) ||
            message.contains("sign-in", ignoreCase = true)
        return ConnectionStatus.Failed(profile, message, needsCredentials)
    }

    // ── Thin RPC facade so features never touch HermesClient directly ───────

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = HermesClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ): JsonObject = client.request(method, params, timeoutMs)

    suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = HermesClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ): Result<JsonObject> = runCatching { request(method, params, timeoutMs) }

    private companion object {
        const val WATCH_TICK_MS = 1_000L
        const val BASE_BACKOFF_MS = 1_500L
        const val MAX_BACKOFF_MS = 30_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
