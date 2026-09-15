package com.materialagent.core

import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.Skin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionState { IDLE, CONNECTING, OPEN, RECONNECTING, CLOSED, ERROR }

/**
 * WebSocket client for the Hermes gateway's newline-delimited JSON-RPC protocol.
 *
 * Deliberately Android-free: it only needs OkHttp, coroutines and kotlinx
 * serialization, so the whole transport can be exercised from JVM unit tests
 * against a real server (see `HermesClientLiveTest`).
 *
 * Responsibilities:
 *  * one request/response channel keyed by JSON-RPC id, with per-call timeouts;
 *  * a hot event stream that every feature observes (`events`);
 *  * liveness — `gateway.ping` every [HEARTBEAT_INTERVAL_MS], and a forced
 *    disconnect when nothing has arrived for [HEARTBEAT_DEADLINE_MS];
 *  * lossless resume — per-session `seq` watermarks plus `session.events.since`
 *    after a reconnect, invalidated when the server's `replay_epoch` changes
 *    (the backend restarted and its counters reset).
 *
 * The client does not decide *whether* to reconnect; [com.materialagent.data.HermesConnection]
 * owns that policy.
 */
class HermesClient(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    /** Server identity, available once `gateway.ready` lands. */
    private val _skin = MutableStateFlow<Skin?>(null)
    val skin: StateFlow<Skin?> = _skin.asStateFlow()

    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** TEMPORARY diagnosis hook: set by HermesConnection to mirror decisions into logcat. */
    @Volatile var trace: ((String) -> Unit)? = null
    private fun note(line: String) { trace?.invoke(line) }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastInboundAt: Long = 0
    @Volatile private var inboundFrames: Long = 0
    @Volatile private var lastInboundAtBoot: Long = 0
    private var heartbeatJob: Job? = null
    private val closing = AtomicBoolean(false)

    /**
     * Whether the last close was a credential rejection (4401/4403).
     *
     * [ConnectionState.ERROR] is reached two very different ways — the server
     * refusing our password, and the transport simply breaking — and they want
     * opposite responses: one has to be shown to the user, the other has to be
     * retried. Keeping the reason here is what lets the connection owner tell
     * them apart. Reset on every dial, so it always describes the current socket.
     */
    @Volatile private var authRejected = false
    val isAuthRejected: Boolean get() = authRejected

    /** Highest `seq` observed per session — the resume watermark. */
    private val watermarks = ConcurrentHashMap<String, Int>()

    @Volatile private var replayEpoch: String? = null
    private val replayInFlight = AtomicBoolean(false)

    val isOpen: Boolean get() = _state.value == ConnectionState.OPEN

    // ── Connection ──────────────────────────────────────────────────────────

    /**
     * Opens the socket and suspends until `gateway.ready` arrives (or fails).
     *
     * [gatewayOpeningTimeoutMs] guards the handshake so a half-open TCP
     * connection can't leave the UI stuck on "Connecting…".
     */
    suspend fun connect(wsUrl: String, openingTimeoutMs: Long = 20_000): Unit {
        disconnect(reason = "reconnect")
        closing.set(false)
        authRejected = false
        _state.value = ConnectionState.CONNECTING

        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(wsUrl).build()
        val listener = Listener(opened)
        val ws = httpClient.newWebSocket(request, listener)
        socket = ws

        lastInboundAtBoot = now()
        inboundFrames = 0
        val ready = withTimeoutOrNull(openingTimeoutMs) { opened.await() }
        if (ready == null) {
            // Either the socket never opened or the server never said hello.
            note("connect TIMEOUT urlsuffix=${wsUrl.takeLast(12)}")
            ws.cancel()
            socket = null
            _state.value = ConnectionState.ERROR
            throw HermesTransportException("Timed out waiting for the gateway handshake")
        }
        note("connect OPEN urlsuffix=${wsUrl.takeLast(12)}")
        _state.value = ConnectionState.OPEN
        startHeartbeat()
        maybeReplay()
    }

    fun disconnect(reason: String = "client closed") {
        closing.set(true)
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close(1000, reason)
        socket = null
        failAllPending(HermesTransportException("Disconnected: $reason"))
        if (_state.value != ConnectionState.CLOSED) _state.value = ConnectionState.CLOSED
    }

    /** Marks the current socket stale without tearing down the URL, so a caller can redial. */
    private fun invalidate(reason: String) {
        note("invalidate: $reason (inboundFrames=$inboundFrames)")
        socket?.cancel()
        socket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        closing.set(true)
        failAllPending(HermesTransportException(reason))
        _state.value = ConnectionState.CLOSED
    }

    // ── Requests ────────────────────────────────────────────────────────────

    @JvmOverloads
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): JsonObject {
        val ws = socket ?: throw HermesTransportException("Gateway not connected")
        if (_state.value != ConnectionState.OPEN) {
            throw HermesTransportException("Gateway not connected")
        }

        val id = "a${requestIds.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred

        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        if (!ws.send(frame.toString())) {
            pending.remove(id)
            throw HermesTransportException("Gateway socket refused the request")
        }

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw HermesTransportException("Request timed out after ${timeoutMs / 1000}s: $method")
            }
        return result
    }

    // ── Inbound handling ────────────────────────────────────────────────────

    private fun handleFrame(raw: String) {
        lastInboundAt = now()
        val text = raw.trim()
        if (text.isEmpty()) return
        // The protocol is newline-delimited, but a single frame may still batch
        // several documents; parse each line defensively.
        text.lineSequence().forEach(::handleDocument)
    }

    private fun handleDocument(document: String) {
        if (document.isBlank()) return
        val element = runCatching { Json.parseToJsonElement(document) }.getOrNull() ?: return
        val obj = element.objOrNull() ?: return

        // Response to one of our requests.
        val id = obj.str("id")
        if (id != null && (obj.containsKey("result") || obj.containsKey("error"))) {
            val deferred = pending.remove(id) ?: return
            val error = obj.obj("error")
            if (error != null) {
                deferred.completeExceptionally(
                    HermesRpcException(error.int("code"), error.str("message") ?: "Gateway error"),
                )
            } else {
                deferred.complete(obj.obj("result") ?: JsonObject(emptyMap()))
            }
            return
        }

        // Server push.
        if (obj.str("method") != "event") return
        val params = obj.obj("params") ?: return
        val type = params.str("type") ?: return
        val event = GatewayEvent(
            type = type,
            sessionId = params.str("session_id")?.takeIf { it.isNotBlank() },
            seq = params.int("seq"),
            payload = params.obj("payload"),
        )

        when (type) {
            GatewayEvent.GATEWAY_READY -> {
                _skin.value = Skin.from(event.payload)
                maybeAdoptEpoch(event.payload.str("replay_epoch"))
            }
            GatewayEvent.SKIN_CHANGED -> _skin.value = Skin.from(event.payload)
            GatewayEvent.BACKGROUND_COMPLETE, GatewayEvent.TURN_ERROR, GatewayEvent.ERROR -> Unit
        }
        _serverVersion.value = event.payload?.str("version") ?: _serverVersion.value

        recordWatermark(event)
        _events.tryEmit(event)
    }

    private fun recordWatermark(event: GatewayEvent) {
        val sid = event.sessionId ?: return
        val seq = event.seq ?: return
        watermarks.compute(sid) { _, prev -> if (prev == null || seq > prev) seq else prev }
    }

    private fun maybeAdoptEpoch(epoch: String?) {
        if (epoch.isNullOrBlank()) return
        val previous = replayEpoch
        if (previous == epoch) return
        if (previous != null) watermarks.clear()
        replayEpoch = epoch
    }

    /**
     * After a reconnect, ask the gateway to re-send anything we missed.
     *
     * Best-effort by design: replay is an optimisation over lossy reconnect,
     * so failures are swallowed and the next reconnect retries.
     */
    private fun maybeReplay() {
        if (watermarks.isEmpty()) return
        if (!replayInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                val snapshot = watermarks.entries.map { it.key to it.value }
                for ((sid, lastSeen) in snapshot) {
                    val result = runCatching {
                        request(
                            "session.events.since",
                            buildJsonObject {
                                put("session_id", JsonPrimitive(sid))
                                put("last_seen", JsonPrimitive(lastSeen))
                            },
                            timeoutMs = REPLAY_TIMEOUT_MS,
                        )
                    }.getOrNull() ?: continue

                    val epoch = result.str("epoch")
                    if (!epoch.isNullOrBlank() && replayEpoch != null && epoch != replayEpoch) {
                        // Backend restarted: old seq numbers describe a world
                        // that no longer exists.
                        maybeAdoptEpoch(epoch)
                        break
                    }
                    result.arr("events")?.forEach { item ->
                        val eventObj = item.objOrNull() ?: return@forEach
                        val type = eventObj.str("type") ?: return@forEach
                        val seq = eventObj.int("seq")
                        val sid2 = eventObj.str("session_id")?.takeIf { it.isNotBlank() } ?: sid
                        val seen = watermarks[sid2]
                        if (seq != null && seen != null && seq <= seen) return@forEach
                        if (seq != null) watermarks[sid2] = seq
                        _events.tryEmit(
                            GatewayEvent(type, sid2, seq, eventObj.obj("payload")),
                        )
                    }
                }
            } catch (_: Throwable) {
                // ignore — replay is opportunistic
            } finally {
                replayInFlight.set(false)
            }
        }
    }

    // ── Liveness ────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastInboundAt = now()
        heartbeatJob = scope.launch {
            var sequence = 0
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (socket == null) return@launch
                val silentFor = now() - lastInboundAt
                if (silentFor >= HEARTBEAT_DEADLINE_MS) {
                    invalidate("Gateway stopped answering (${silentFor / 1000}s of silence)")
                    return@launch
                }
                val ws = socket ?: return@launch
                sequence += 1
                val ping = buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive("heartbeat-$sequence"))
                    put("method", JsonPrimitive("gateway.ping"))
                    put("params", JsonObject(emptyMap()))
                }
                if (!ws.send(ping.toString())) {
                    // OkHttp answers false once the socket is already failed. The
                    // silence clock below cannot see a dead socket until its own
                    // deadline; this says so now, in the one place that talks to
                    // the socket every 15 seconds.
                    invalidate("Gateway socket refused the heartbeat")
                    return@launch
                }
            }
        }
    }

    /**
     * Re-checks liveness after the app comes back to the foreground, and reports
     * whether the socket can still be trusted.
     *
     * [startHeartbeat] is a coroutine parked on `delay`, and a backgrounded
     * Android app is frozen: the process is not scheduled, so no ping goes out
     * and the silence deadline above never gets to fire. The gateway, which is
     * still watching for that ping, will have dropped us. So on resume the clock
     * has to be read directly rather than trusting a heartbeat that was asleep —
     * otherwise the socket reports OPEN while the far end has already let go, and
     * the user only finds out when a message hangs.
     *
     * Silence is still not proof of life, which is why this asks the gateway
     * rather than only reading the clock. Two gaps make the clock alone unsafe:
     * a socket can die during a *short* absence, before the deadline is anywhere
     * near; and the failure notification and `ON_START` are delivered at the same
     * moment, so whenever the main thread is scheduled first the client is still
     * reporting OPEN when this runs. A `gateway.ping` costs one round trip and
     * settles it, so a message sent straight after returning to the app goes to a
     * socket that has actually been checked.
     */
    suspend fun checkLivenessOnForeground(): Boolean {
        note("checkLiveness socket=${if (socket == null) "null" else "live"} silence=${(now() - lastInboundAt) / 1000}s state=${_state.value}")
        if (socket == null) return false
        val silentFor = now() - lastInboundAt
        if (silentFor >= HEARTBEAT_DEADLINE_MS) {
            invalidate(
                "Gateway went quiet while the app was in the background " +
                    "(${silentFor / 1000}s of silence)",
            )
            return false
        }
        val answered = runCatching {
            request("gateway.ping", timeoutMs = FOREGROUND_PROBE_TIMEOUT_MS)
        }.isSuccess
        if (!answered) {
            invalidate("Gateway did not answer the foreground probe")
            return false
        }
        return true
    }

    private fun failAllPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    /** Test seam: current resume watermarks. */
    fun watermarks(): Map<String, Int> = watermarks.toMap()

    // ── Socket callbacks ────────────────────────────────────────────────────

    private inner class Listener(private val opened: CompletableDeferred<Unit>) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            runCatching { handleFrame(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            note("onFailure ${t::class.java.simpleName}: ${t.message} (inbound=$inboundFrames)")
            if (!opened.isCompleted) opened.completeExceptionally(t)
            socket = null
            heartbeatJob?.cancel()
            failAllPending(HermesTransportException(t.message ?: "Socket failure", t))
            if (!closing.get()) _state.value = ConnectionState.ERROR
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            note("onClosed code=$code reason=$reason (inbound=$inboundFrames)")
            socket = null
            heartbeatJob?.cancel()
            if (!opened.isCompleted) {
                opened.completeExceptionally(HermesTransportException("Closed before handshake: $code $reason"))
            }
            failAllPending(HermesTransportException("Socket closed ($code) $reason"))
            // 4401/4403 are credential failures — surface them as ERROR so the
            // connection owner can prompt for re-authentication instead of
            // silently retrying forever. Recording *why* is what lets it keep
            // retrying everything else.
            authRejected = code == 4401 || code == 4403
            _state.value = if (authRejected) ConnectionState.ERROR else ConnectionState.CLOSED
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
        private const val REPLAY_TIMEOUT_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val HEARTBEAT_DEADLINE_MS = 45_000L

        /**
         * How long the foreground probe waits for `gateway.ping`.
         *
         * Short by design: the gateway answers pings off its own event loop, so
         * anything that has not replied within seconds is a socket the user does
         * not want their next message written into. Orders of magnitude above the
         * round trip this normally takes.
         */
        const val FOREGROUND_PROBE_TIMEOUT_MS = 8_000L
    }
}
