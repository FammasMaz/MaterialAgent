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

    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastInboundAt: Long = 0
    @Volatile private var connectedUrl: String? = null
    private var heartbeatJob: Job? = null
    private val closing = AtomicBoolean(false)

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
        connectedUrl = wsUrl
        _state.value = ConnectionState.CONNECTING

        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder().url(wsUrl).build()
        val listener = Listener(opened)
        val ws = httpClient.newWebSocket(request, listener)
        socket = ws

        val ready = withTimeoutOrNull(openingTimeoutMs) { opened.await() }
        if (ready == null) {
            // Either the socket never opened or the server never said hello.
            ws.cancel()
            socket = null
            _state.value = ConnectionState.ERROR
            throw HermesTransportException("Timed out waiting for the gateway handshake")
        }
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
                ws.send(ping.toString())
            }
        }
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
            socket = null
            heartbeatJob?.cancel()
            if (!opened.isCompleted) {
                opened.completeExceptionally(HermesTransportException("Closed before handshake: $code $reason"))
            }
            failAllPending(HermesTransportException("Socket closed ($code) $reason"))
            // 4401/4403 are credential failures — surface them as ERROR so the
            // connection owner can prompt for re-authentication instead of
            // silently retrying forever.
            _state.value = when (code) {
                4401, 4403 -> ConnectionState.ERROR
                else -> ConnectionState.CLOSED
            }
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 120_000L
        private const val REPLAY_TIMEOUT_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val HEARTBEAT_DEADLINE_MS = 45_000L
    }
}
