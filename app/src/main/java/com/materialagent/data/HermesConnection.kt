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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val secrets: SecretSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    val client = HermesClient(http, scope, now)

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

    /** Which attempt owns the status; see [AttemptGate]. */
    private val attempts = AttemptGate()

    /** Publishes [status] only while [attemptId] is still the newest attempt. */
    private fun publish(attemptId: Int, status: ConnectionStatus) {
        if (attempts.allows(attemptId)) {
            _status.value = status
        } else {
        }
    }

    suspend fun activate(profile: ServerProfile): ConnectionStatus {
        intentionalStop = false
        stopWatcher()
        activeProfile = profile
        // Claimed before dialling, so this connect supersedes anything already in
        // flight and cannot be undone afterwards by the redial it replaced.
        val attemptId = attempts.claim()
        publish(attemptId, ConnectionStatus.Connecting(profile))
        return runCatching { dial(profile) }
            .fold(
                onSuccess = {
                    val connected = ConnectionStatus.Connected(profile)
                    publish(attemptId, connected)
                    startWatcher(profile)
                    connected
                },
                onFailure = { error ->
                    val failure = classify(profile, error)
                    publish(attemptId, failure)
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
        publish(attempts.claim(), ConnectionStatus.Idle)
    }

    /** Re-dials the active profile; used by the "Retry" affordance. */
    fun retry() {
        val profile = activeProfile ?: return
        intentionalStop = false
        stopWatcher()
        // Claimed and published before the redial is launched: the status is never
        // left reading Idle while an attempt is on its way, and the number the
        // coroutine publishes its outcome under is already the newest.
        val attemptId = attempts.claim()
        publish(attemptId, ConnectionStatus.Connecting(profile))
        reconnectJob = scope.launch {
            runCatching { dial(profile) }.fold(
                onSuccess = {
                    publish(attemptId, ConnectionStatus.Connected(profile))
                    startWatcher(profile)
                },
                onFailure = { publish(attemptId, classify(profile, it)) },
            )
        }
    }

    /**
     * Called when the app returns to the foreground.
     *
     * A frozen process cannot run the heartbeat, so the gateway may have dropped
     * us while we were away even though the in-memory state still reads OPEN.
     * Rather than let the user discover that by sending a message that hangs for
     * two minutes, liveness is judged now and the socket is re-dialled before they
     * touch anything. Reconnecting also replays the session's missed events, so an
     * answer that arrived in the background is on screen when they get back.
     */
    fun onForeground() {
        val profile = activeProfile ?: return
        if (intentionalStop) return
        // A dial is genuinely in flight (the launch connect, or a redial that is
        // mid-handshake). Starting a second one on top of it is how the app ended
        // up connected but labelled disconnected: both dialled, the first to
        // finish cancelled the other, and the cancelled dial's verdict was
        // published last. Whoever is already dialling will report its own outcome.
        //
        // Reconnecting does *not* count: that status is also what the watcher
        // publishes while it sits in its backoff, and making the user wait out a
        // 30 s timer behind a banner is the same bug from the outside.
        if (_status.value is ConnectionStatus.Connecting) return
        scope.launch {
            // Not "does the state read OPEN": ask the gateway. A socket that died
            // while we were away still reads OPEN, because the failure callback
            // and ON_START arrive together and ON_START wins whenever the main
            // thread is scheduled first.
            if (client.checkLivenessOnForeground()) return@launch
            if (intentionalStop || activeProfile?.id != profile.id) return@launch
            retry()
        }
    }

    /**
     * Opens the socket and completes the handshake. Deliberately does *not* start
     * the drop watcher: [startWatcher] owns that job, and a redial from inside the
     * watcher must not cancel the watcher that is running it.
     */
    private suspend fun dial(profile: ServerProfile) {
        // Password sign-in reaches the gateway through blocking OkHttp calls
        // (provider discovery, /auth/password-login, /api/auth/ws-ticket). Those
        // are called from a ViewModel scope, which is the main dispatcher, so
        // doing them inline throws NetworkOnMainThreadException on Android the
        // moment the user picks Password — the token path only escapes because it
        // needs no HTTP at all.
        val authParam = withContext(Dispatchers.IO) { resolveAuth(profile) }
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
        // The watcher is an attempt in its own right: it owns the status for as
        // long as the socket lives, and every write below carries its number so a
        // later connect silences it by claiming the next one.
        val attemptId = attempts.claim()
        reconnectJob = scope.launch {
            var attempt = 0
            while (!intentionalStop && activeProfile?.id == profile.id) {
                delay(WATCH_TICK_MS)
                if (client.state.value == com.materialagent.core.ConnectionState.OPEN) {
                    if (attempt != 0) {
                        attempt = 0
                        publish(attemptId, ConnectionStatus.Connected(profile))
                    }
                    continue
                }
                // Ending the watcher is reserved for a rejected credential, which
                // no amount of retrying can fix. Every other way of reaching ERROR
                // — the gateway dropping us while the app was frozen, a wifi/cell
                // handover, a gateway restart, a redial that timed out — is
                // transient, and giving up on it left a reachable gateway showing
                // "Connection lost" until the user tapped Retry by hand. The
                // backoff ladder below only ever ran once because of it.
                if (client.isAuthRejected) {
                    publish(
                        attemptId,
                        ConnectionStatus.Failed(
                            profile,
                            "${profile.name} rejected the stored sign-in. Sign in again.",
                            needsCredentials = true,
                        ),
                    )
                    return@launch
                }
                attempt += 1
                publish(attemptId, ConnectionStatus.Reconnecting(profile, attempt))
                delay(backoffFor(attempt))
                if (intentionalStop || activeProfile?.id != profile.id) return@launch
                val error = runCatching { dial(profile) }.exceptionOrNull()
                if (error == null) {
                    attempt = 0
                    publish(attemptId, ConnectionStatus.Connected(profile))
                    continue
                }
                // A redial can fail for a reason retrying cannot fix either: the
                // password was changed on the server, or sign-in is being
                // rate-limited. classify() already knows which failures those are.
                val failure = classify(profile, error)
                if (failure is ConnectionStatus.Failed && failure.needsCredentials) {
                    publish(attemptId, failure)
                    return@launch
                }
            }
        }
    }

    /** Exponential with a ceiling: 1.5 s, 3 s, 6 s, 12 s, 24 s, then 30 s. */
    private fun backoffFor(attempt: Int): Long =
        (BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(4))).coerceAtMost(MAX_BACKOFF_MS)

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
                "${profile.baseUrl} answered, but offers no password sign-in. " +
                    "Add a password provider on the server, or use a token.",
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

    /**
     * Finds a session provider that accepts a password.
     *
     * Returns null only when the server *answered* and genuinely offers none.
     * Anything else throws, because the old shape — swallowing every failure
     * through `runCatching` — reported "this server has no password sign-in"
     * even when the phone never reached it, which blames the server for a
     * transport problem and sends the user off to change server config that was
     * never wrong.
     */
    private fun discoverPasswordProvider(profile: ServerProfile): String? {
        val url = HermesUrl.endpoint(profile.baseUrl, "/api/auth/providers")
            ?: throw HermesTransportException("Not a valid server address: ${profile.baseUrl}")

        val body = try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesTransportException(
                        when (response.code) {
                            // The gateway rejects any Host it has not been told about, and
                            // dashboard.public_url is the only name it trusts for that.
                            in listOf(400, 403, 421) ->
                                "${profile.baseUrl} refused this address (HTTP ${response.code}). " +
                                    "The gateway only trusts its configured dashboard.public_url."

                            404 ->
                                "${profile.baseUrl} has no sign-in endpoint. " +
                                    "It is probably not a Hermes gateway, or it is an older build."

                            else ->
                                "Could not check sign-in options at ${profile.baseUrl} (HTTP ${response.code})."
                        },
                    )
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw HermesTransportException(
                "Could not reach ${profile.baseUrl} to check sign-in: " +
                    (e.message ?: e::class.java.simpleName),
            )
        }

        val root = runCatching { HermesJson.parseToJsonElement(body).objOrNull() }.getOrNull()
            ?: throw HermesTransportException(
                "${profile.baseUrl} did not answer with a provider list (unexpected reply shape).",
            )
        val providers = root["providers"]?.arrOrNull() ?: return null
        return providers.mapNotNull { it.objOrNull() }
            .firstOrNull { it.str("supports_password")?.equals("true", ignoreCase = true) == true }
            ?.str("name")
    }

    /**
     * Turns a dial failure into something the UI can say truthfully.
     *
     * A plain cancellation is NOT a failure: the attempt was abandoned, usually because
     * the user left the screen, and there is nothing about the server to report. Treating
     * it as one produced a "cancelled before it finished" banner that outlived the outage
     * — a later connect flips the status to Connected, but the banner the UI had already
     * surfaced stayed on screen above a fully loaded session list. Timeouts are
     * cancellations too and genuinely are failures, so they keep their own branch below.
     */
    internal fun classify(profile: ServerProfile, error: Throwable): ConnectionStatus {
        if (error is CancellationException && error !is TimeoutCancellationException) {
            return ConnectionStatus.Idle
        }
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

            // The socket opened, so the address and port are right, but the
            // handshake never completed. Without its own branch this reported
            // only "Connection failed", because a timeout carries no message.
            is TimeoutCancellationException ->
                "${profile.baseUrl} accepted the connection but never finished the " +
                    "handshake. Something between here and the gateway — a proxy, a " +
                    "tunnel, a firewall — is not passing the WebSocket through."

            is HermesTransportException ->
                error.message ?: "Connection to ${profile.baseUrl} failed."

            is IOException -> "Could not reach ${profile.baseUrl}. Is `hermes serve` running?"

            // Anything else used to collapse to a bare "Connection failed" and
            // throw away the one clue that would explain it. Name the type.
            else -> buildString {
                append(error.message ?: "Connection failed")
                append(" (")
                append(error::class.java.simpleName)
                error.cause?.let { cause ->
                    append(": ")
                    append(cause.message ?: cause::class.java.simpleName)
                }
                append(")")
            }
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
