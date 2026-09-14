package com.materialagent.live

import com.materialagent.core.ConnectionState
import com.materialagent.core.HermesClient
import com.materialagent.core.HermesRpcException
import com.materialagent.core.arr
import com.materialagent.core.int
import com.materialagent.core.obj
import com.materialagent.core.objOrNull
import com.materialagent.core.str
import com.materialagent.core.model.GatewayEvent
import com.materialagent.core.model.SessionInfo
import com.materialagent.core.model.SessionSummary
import com.materialagent.core.model.Usage
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live end-to-end test: the real [HermesClient] against a real Hermes gateway
 * over a real WebSocket.
 *
 * It drives the same RPC shapes [com.materialagent.data.SessionRepository] and
 * [com.materialagent.data.ChatController] put on the wire in the app, and pushes
 * every reply back through the app's own mappers (`SessionSummary.from`,
 * `SessionInfo.from`, `Usage.from`), so a protocol drift breaks the test rather
 * than the phone.
 *
 * This class is deliberately *not* `@Ignore`d. It is gated at runtime: when
 * nothing is listening on the gateway port the whole test is skipped through
 * [assumeTrue], so ordinary `:app:testDebugUnitTest` runs stay green offline.
 * When a server *is* reachable, failures are real failures — a failed turn is
 * reported with the gateway's own words, never papered over.
 *
 * Start the server side with `scripts/tunnel.sh` (see `HermesLiveReadme.md`).
 */
class HermesLiveTest {

    /**
     * One full turn: connect → list → create → prompt → stream → verify → clean up.
     *
     * Everything runs on the JVM; the transport is Android-free by design, so no
     * Android framework class is touched here.
     */
    @Test(timeout = 360_000)
    fun realGatewayTurnRoundTrip() {
        val wsUrl = System.getenv("HERMES_TEST_WS")?.takeIf { it.isNotBlank() } ?: DEFAULT_WS_URL

        // Precondition, not assertion: a missing server means "nothing to test",
        // and a build must not fail just because the tunnel is down.
        assumeTrue(
            "Skipping: nothing listening at $wsUrl — run scripts/tunnel.sh first.",
            gatewayReachable(wsUrl),
        )

        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // A turn can take minutes; a short read timeout would tear the socket
            // down while the model is still thinking.
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // The client needs its own scope: its heartbeat loop never returns, so
        // sharing runBlocking's scope would stop the test from ever finishing.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(http, scope)
        val transcript = StringBuilder()
        fun note(line: String) {
            transcript.appendLine(line)
        }

        try {
            runBlocking { driveTurn(client, wsUrl, ::note) }
        } finally {
            client.disconnect("live test finished")
            scope.cancel()
            println("──────── hermes live run ────────")
            print(transcript)
            println("─────────────────────────────────")
        }
    }

    /**
     * Pins the branch contract, which is not the shape it looks like.
     *
     * `session.branch` identifies its source by the **runtime** id from
     * `session.create`/`session.resume`, and answers "session not found" (4001)
     * for the stored id that `session.list` shows and that every other session
     * method accepts. The app shipped branching against the stored id and it
     * silently did nothing on a real server, so the negative case is asserted
     * here too — if the gateway ever starts accepting stored ids this test fails
     * and the extra `session.resume` round trip can be deleted.
     */
    @Test(timeout = 360_000)
    fun branchNeedsTheRuntimeSessionId() {
        val wsUrl = System.getenv("HERMES_TEST_WS")?.takeIf { it.isNotBlank() } ?: DEFAULT_WS_URL
        assumeTrue(
            "Skipping: nothing listening at $wsUrl — run scripts/tunnel.sh first.",
            gatewayReachable(wsUrl),
        )

        val http = OkHttpClient.Builder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(http, scope)
        val transcript = StringBuilder()
        fun note(line: String) {
            transcript.appendLine(line)
        }

        var runtime: String? = null
        var stored: String? = null
        var branchStored: String? = null
        var branchRuntime: String? = null
        try {
            runBlocking {
                client.connect(wsUrl, openingTimeoutMs = 30_000)
                waitUntil(HANDSHAKE_TIMEOUT_MS) { client.skin.value != null }

                val created = client.request(
                    "session.create",
                    buildJsonObject {
                        put("source", JsonPrimitive("mobile"))
                        put("title", JsonPrimitive("MaterialAgent branch test"))
                    },
                    timeoutMs = REQUEST_TIMEOUT_MS,
                )
                runtime = created.str("session_id").orEmpty()
                stored = created.str("stored_session_id").orEmpty()
                note("source     runtime=$runtime stored=$stored")
                assertTrue("source session needs a runtime id", runtime.orEmpty().isNotBlank())
                assertTrue("source session needs a stored id", stored.orEmpty().isNotBlank())

                // A session is only persisted once a turn completes, so branch
                // from a conversation with something in it.
                client.request(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", JsonPrimitive(runtime))
                        put("text", JsonPrimitive("Reply with exactly the word: branched"))
                    },
                    timeoutMs = REQUEST_TIMEOUT_MS,
                )
                assertNotNull(
                    "the seeded turn must finish before branching",
                    awaitSessionInList(client, stored.orEmpty(), LIST_TIMEOUT_MS, ::note),
                )

                // The stored id is what the app has for a row it has not opened.
                val viaStored = runCatching {
                    client.request("session.branch", idParams(stored.orEmpty()), timeoutMs = REQUEST_TIMEOUT_MS)
                }
                val storedFailure = viaStored.exceptionOrNull()
                note("branch(stored) -> ${if (viaStored.isSuccess) "accepted" else describe(storedFailure)}")
                assertNotNull(
                    "branching by stored id is expected to be rejected (4001); " +
                        "if this now succeeds the resume-first workaround can go",
                    storedFailure,
                )

                // ...and the runtime id is what actually works.
                val branched = client.request("session.branch", idParams(runtime), timeoutMs = REQUEST_TIMEOUT_MS)
                branchStored = branched.str("stored_session_id")
                branchRuntime = branched.str("session_id")
                note(
                    "branch(runtime) -> runtime=${branched.str("session_id")} " +
                        "stored=$branchStored messages=${branched.num("message_count")}",
                )
                assertTrue(
                    "a branch must be its own stored session",
                    !branchStored.isNullOrBlank() && branchStored != stored,
                )
                // How much history a branch carries is the gateway's call — it
                // forks at a point in the conversation — so the depth is recorded
                // as evidence rather than asserted at a magic number. What the app
                // needs is the branch response's *messages* shape, since the new
                // conversation is opened from the stored id it just received.
                note(
                    "branch     message_count=${branched.num("message_count") ?: "<absent>"} " +
                        "messages=${branched.arr("messages")?.size ?: 0} " +
                        "parent=${branched.str("parent") ?: "<absent>"}",
                )
                val reopened = client.request(
                    "session.resume",
                    idParams(branchStored.orEmpty()),
                    timeoutMs = REQUEST_TIMEOUT_MS,
                )
                note(
                    "reopened   runtime=${reopened.str("session_id")} " +
                        "rows=${reopened.arr("messages")?.size ?: 0} " +
                        "title=${reopened.obj("info")?.str("title") ?: "<absent>"}",
                )
                assertTrue(
                    "a branch the app just created must be openable by its stored id",
                    !reopened.str("session_id").isNullOrBlank(),
                )
            }
        } finally {
            if (branchRuntime != null || branchStored != null) {
                runBlocking {
                    cleanup(client, branchRuntime, branchStored, ::note)
                    cleanup(client, runtime, stored, ::note)
                }
            }
            client.disconnect("branch test finished")
            scope.cancel()
            println("──────── hermes branch run ────────")
            print(transcript)
            println("───────────────────────────────────")
        }
    }

    // ── The turn ────────────────────────────────────────────────────────────

    private suspend fun driveTurn(client: HermesClient, wsUrl: String, note: (String) -> Unit) {
        // Subscribe before connecting: the event flow has replay = 0, so anything
        // emitted before a collector attaches is gone for good.
        val events = CopyOnWriteArrayList<GatewayEvent>()
        val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        collectorScope.launch { client.events.collect { events.add(it) } }

        var runtimeSessionId: String? = null
        var storedSessionId: String? = null

        try {
            // 1 ── connect. ConnectionState.OPEN is the transport state that
            //      HermesConnection reports to the UI as ConnectionStatus.Connected.
            val connectStartedAt = System.currentTimeMillis()
            client.connect(wsUrl, openingTimeoutMs = 30_000)
            assertEquals(
                "transport state after a successful handshake",
                ConnectionState.OPEN,
                client.state.value,
            )
            // gateway.ready lands just after onOpen; give the handshake a moment.
            waitUntil(HANDSHAKE_TIMEOUT_MS) { client.skin.value != null }
            note("connected  ${System.currentTimeMillis() - connectStartedAt} ms  state=${client.state.value}")
            note(
                "handshake  skin=${client.skin.value?.name ?: "<none>"}" +
                    " agent=${client.skin.value?.branding?.agentName ?: "?"}" +
                    " version=${client.serverVersion.value ?: "<absent from gateway.ready>"}",
            )
            // The app builds its optional Hermes skin from this payload, so an
            // empty skin means the handshake shape moved.
            assertNotNull("gateway.ready must deliver a skin payload", client.skin.value)

            // 2 ── session.list. Assert the shape, never the length: an empty
            //      catalogue is a perfectly good answer.
            val summaries = parseSessionList(client.request("session.list", timeoutMs = REQUEST_TIMEOUT_MS))
            note("listed     ${summaries.size} row(s); length is not asserted")
            summaries.take(3).forEach {
                note("           · ${it.id}  src=${it.source}  msgs=${it.messageCount}  title=${it.title}")
            }

            // 3 ── session.create.
            val created = client.request(
                "session.create",
                buildJsonObject {
                    put("source", JsonPrimitive("mobile"))
                    put("title", JsonPrimitive("MaterialAgent live test"))
                },
                timeoutMs = REQUEST_TIMEOUT_MS,
            )
            runtimeSessionId = created.str("session_id").orEmpty()
            storedSessionId = created.str("stored_session_id")
            note("created    runtime=$runtimeSessionId stored=$storedSessionId")
            assertTrue("session.create must return a non-blank session_id", runtimeSessionId.isNotBlank())
            assertTrue(
                "session.create must return a non-blank stored_session_id (verification and cleanup need it)",
                !storedSessionId.isNullOrBlank(),
            )
            val liveId = runtimeSessionId
            val storedId = storedSessionId.orEmpty()

            // 4 ── prompt.submit, then watch the event stream until the turn ends.
            val turnStartedAt = System.currentTimeMillis()
            val submitted = client.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", JsonPrimitive(liveId))
                    put("text", JsonPrimitive(PROMPT))
                },
                timeoutMs = REQUEST_TIMEOUT_MS,
            )
            note("submitted  $submitted  prompt=\"$PROMPT\"")

            val complete = awaitMessageComplete(events, liveId, turnStartedAt, note)
            val turnMillis = System.currentTimeMillis() - turnStartedAt

            val deltas = events.filter {
                it.sessionId == liveId && it.type == GatewayEvent.MESSAGE_DELTA && !it.text.isNullOrEmpty()
            }
            val interims = events.filter {
                it.sessionId == liveId && it.type == GatewayEvent.MESSAGE_INTERIM && !it.text.isNullOrEmpty()
            }
            val streamed = deltas.joinToString(separator = "") { it.text.orEmpty() }
            val finalText = complete.text.orEmpty()

            note("turn       ${turnMillis / 1000}s  complete.status=${complete.status ?: "<absent>"}")
            note("           message.delta=${deltas.size} message.interim=${interims.size} message.complete=1")
            note("           streamed=\"$streamed\"")
            note("           complete=\"$finalText\"")
            reportSessionInfo(note, events, liveId)
            reportUsage(note, complete)

            assertNotEquals(
                "the gateway reported a failed turn: ${complete.payload?.str("error") ?: complete.payload}",
                "error",
                complete.status,
            )
            assertTrue(
                "expected at least one message.delta-family event",
                deltas.isNotEmpty() || interims.isNotEmpty(),
            )
            assertTrue("the assistant text streamed to the client was empty", streamed.isNotBlank())
            assertTrue("message.complete carried no assistant text", finalText.isNotBlank())

            // 5 ── the durable catalogue must now know this conversation. Titles
            //      are generated asynchronously (and the explicit title above may
            //      lose to a generated one), so only presence and provenance are
            //      asserted — never title text.
            val summary = awaitSessionInList(client, storedId, LIST_TIMEOUT_MS, note)
            assertNotNull("stored session $storedId never showed up in session.list", summary)
            val entry = summary!!
            note("catalogue  id=${entry.id} src=${entry.source} msgs=${entry.messageCount} title=${entry.title}")
            assertEquals(
                "session.create sent source=mobile, but the catalogue disagrees — " +
                    "the gateway ignores unknown params, so a renamed param fails silently",
                "mobile",
                entry.source,
            )
            assertTrue("a completed turn should leave messages behind, got ${entry.messageCount}", entry.messageCount > 0)

            reportHistory(note, client, liveId)
        } finally {
            cleanup(client, runtimeSessionId, storedSessionId, note)
            collectorScope.cancel()
        }
    }

    /**
     * Waits for the turn's terminal `message.complete`.
     *
     * A mid-turn `turn.error` fails immediately with the gateway's own payload
     * instead of burning the whole timeout, which is what makes a server-side
     * problem readable rather than mysterious.
     */
    private suspend fun awaitMessageComplete(
        events: List<GatewayEvent>,
        sessionId: String,
        startedAt: Long,
        note: (String) -> Unit,
    ): GatewayEvent {
        val deadline = startedAt + TURN_TIMEOUT_MS
        var lastReport = 0L
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { it.sessionId == sessionId && it.type == GatewayEvent.MESSAGE_COMPLETE }
                ?.let { return it }

            events.firstOrNull {
                it.sessionId == sessionId &&
                    (it.type == GatewayEvent.TURN_ERROR || it.type == GatewayEvent.ERROR)
            }?.let { failure ->
                failed("gateway reported ${failure.type} for $sessionId: ${failure.payload}")
            }

            val waited = System.currentTimeMillis() - startedAt
            if (waited - lastReport >= PROGRESS_EVERY_MS) {
                lastReport = waited
                note("waiting    ${waited / 1000}s / ${TURN_TIMEOUT_MS / 1000}s  (turn still running)")
            }
            delay(200)
        }
        failed(
            "no message.complete for $sessionId within ${TURN_TIMEOUT_MS / 1000}s; " +
                "event types seen: ${events.map { it.type }.distinct().sorted()}",
        )
    }

    /** Polls `session.list` until the stored id shows up, or gives up. */
    private suspend fun awaitSessionInList(
        client: HermesClient,
        storedId: String,
        timeoutMs: Long,
        note: (String) -> Unit,
    ): SessionSummary? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching { parseSessionList(client.request("session.list", timeoutMs = REQUEST_TIMEOUT_MS)) }
                .getOrNull()
                ?.firstOrNull { it.id == storedId }
            if (found != null) return found
            delay(1_000)
        }
        note("catalogue  gave up after ${timeoutMs / 1000}s waiting for $storedId")
        return null
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Leaves the user's server as it was found.
     *
     * `session.delete` needs a closed session, and it also refuses a session the
     * gateway never persisted (a runtime session with no completed turn), so the
     * retry loop tolerates the transient versions of both.
     */
    private suspend fun cleanup(
        client: HermesClient,
        runtimeSessionId: String?,
        storedSessionId: String?,
        note: (String) -> Unit,
    ) {
        if (runtimeSessionId != null) {
            val closed = runCatching { client.request("session.close", idParams(runtimeSessionId)) }
            note("closed     $runtimeSessionId -> ${closed.getOrNull() ?: describe(closed.exceptionOrNull())}")
        }
        if (storedSessionId != null) {
            var lastFailure: String? = null
            repeat(3) { attempt ->
                val deleted = runCatching { client.request("session.delete", idParams(storedSessionId)) }
                if (deleted.isSuccess) {
                    note("deleted    $storedSessionId -> ${deleted.getOrNull()}")
                    return
                }
                lastFailure = describe(deleted.exceptionOrNull())
                if (attempt < 2) delay(1_000)
            }
            note("WARNING    could not delete $storedSessionId: $lastFailure")
        }
    }

    // ── Reporting helpers (evidence, no assertions) ─────────────────────────

    /** Exercises `SessionInfo.from` against live data; a null means shape drift. */
    private fun reportSessionInfo(note: (String) -> Unit, events: List<GatewayEvent>, sessionId: String) {
        val payload = events.firstOrNull { it.sessionId == sessionId && it.type == GatewayEvent.SESSION_INFO }?.payload
        val info = SessionInfo.from(payload)
        if (info == null) {
            note("session    no parseable session.info arrived")
            return
        }
        note(
            "session    model=${info.model ?: "?"} provider=${info.provider ?: "?"}" +
                " effort=${info.reasoningEffort ?: "?"} cwd=${info.cwd ?: "?"}",
        )
    }

    /** Exercises `Usage.from` against the completion's usage block. */
    private fun reportUsage(note: (String) -> Unit, complete: GatewayEvent) {
        val usage = Usage.from(complete.payload?.obj("usage"))
        if (usage == null) {
            note("usage      message.complete carried no usage block")
            return
        }
        note(
            "usage      model=${usage.model ?: "?"} input=${usage.input} output=${usage.output}" +
                " total=${usage.total} context=${usage.contextUsed}/${usage.contextMax}",
        )
    }

    /** Informational only: what the server persisted for this live session. */
    private suspend fun reportHistory(note: (String) -> Unit, client: HermesClient, sessionId: String) {
        runCatching { client.request("session.history", idParams(sessionId)) }
            .onSuccess { payload ->
                val rows = payload.arr("messages").orEmpty()
                val roles = rows.mapNotNull { it.objOrNull()?.str("role") }
                note("history    ${payload.int("count") ?: rows.size} row(s) roles=$roles")
            }
            .onFailure { note("history    unavailable: ${describe(it)}") }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Parses a `session.list` payload with the app's own mapper.
     *
     * Strict on purpose: every row must parse, which is exactly the guarantee the
     * UI depends on. Length is never asserted — an empty catalogue is valid.
     */
    private fun parseSessionList(payload: JsonObject): List<SessionSummary> {
        val rows = payload.arr("sessions")
            ?: failed("session.list returned no 'sessions' array: $payload")
        val parsed = rows.mapNotNull { it.objOrNull()?.let(SessionSummary::from) }
        assertEquals(
            "every session.list row must parse through SessionSummary.from",
            rows.size,
            parsed.size,
        )
        assertTrue("every parsed session needs a non-blank id", parsed.all { it.id.isNotBlank() })
        return parsed
    }

    /** Reads a numeric field off a payload that may or may not carry it. */
    private fun JsonObject.num(key: String): Int? = int(key)

    private fun idParams(sessionId: String): JsonObject = buildJsonObject {
        put("session_id", JsonPrimitive(sessionId))
    }

    /** Server errors are quoted verbatim so a failure explains itself. */
    private fun describe(error: Throwable?): String = when (error) {
        null -> "no error"
        is HermesRpcException -> "rpc ${error.code}: ${error.message}"
        else -> "${error::class.simpleName}: ${error.message}"
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(100)
        }
    }

    /**
     * Cheap liveness probe: a TCP connect to the gateway port.
     *
     * Deliberately *not* an HTTP health check — half a gateway (port open, no
     * handshake) should fail loudly, and only a dead port should skip.
     */
    private fun gatewayReachable(wsUrl: String): Boolean = runCatching {
        val uri = URI(wsUrl)
        val port = when {
            uri.port != -1 -> uri.port
            uri.scheme == "wss" -> 443
            else -> 80
        }
        Socket().use { it.connect(InetSocketAddress(uri.host, port), PROBE_TIMEOUT_MS.toInt()) }
    }.isSuccess

    private fun failed(message: String): Nothing = throw AssertionError(message)

    private fun assertNotEquals(message: String, unexpected: String?, actual: String?) {
        if (unexpected == actual) throw AssertionError("$message (both were '$actual')")
    }

    private companion object {
        /** Loopback dev tunnel. Override with HERMES_TEST_WS to point elsewhere. */
        const val DEFAULT_WS_URL = "ws://127.0.0.1:19119/api/ws?token=example-token"

        /** Cheap, deterministic: a model that is merely online can answer this. */
        const val PROMPT = "Reply with exactly the word: pong"

        const val REQUEST_TIMEOUT_MS = 30_000L
        const val HANDSHAKE_TIMEOUT_MS = 15_000L

        /** A real turn can take minutes; the budget is generous on purpose. */
        const val TURN_TIMEOUT_MS = 120_000L
        const val LIST_TIMEOUT_MS = 45_000L
        const val PROGRESS_EVERY_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 2_000L
    }
}
