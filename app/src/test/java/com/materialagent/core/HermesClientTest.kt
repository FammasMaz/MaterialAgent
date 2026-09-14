package com.materialagent.core

import com.materialagent.core.model.GatewayEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real [HermesClient] against a real WebSocket served by
 * [MockWebServer] — no client-side mock.
 *
 * The invariants: requests leave as a single-line JSON-RPC frame with
 * incrementing `a1, a2, …` ids; a matching `result` frame resolves that call and
 * an `error` frame fails it with the server's own message; id-less server pushes
 * reach the shared `events` flow as [GatewayEvent]; and an unanswered request
 * gives up by timeout instead of hanging forever.
 *
 * These tests use real sockets, so the coroutine bodies run on [runBlocking]
 * (real wall-clock) rather than `runTest` virtual time — a virtual clock would
 * fire the client's `withTimeoutOrNull` before the socket had a chance to reply.
 */
class HermesClientTest {

    @Test
    fun requestIsOneJsonLineWithIncrementingIdAndCompletesWithResult() = runBlocking {
        val frames = CopyOnWriteArrayList<String>()
        val ws = servedWebSocket(
            onMessage = { socket, text ->
                frames += text
                val id = Json.parseToJsonElement(text).objOrNull()!!.str("id")!!
                socket.send("""{"jsonrpc":"2.0","id":"$id","result":{"echo":"$id"}}""")
            },
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        server.start()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(OkHttpClient(), scope)
        try {
            client.connect(wsUrl(server))
            val first = client.request("session.list")
            val second = client.request("session.create")

            assertEquals("a1", first.str("echo"))
            assertEquals("a2", second.str("echo"))

            assertEquals(2, frames.size)
            val sent = Json.parseToJsonElement(frames[0]).objOrNull()!!
            assertEquals("2.0", sent.str("jsonrpc"))
            assertEquals("a1", sent.str("id"))
            assertEquals("session.list", sent.str("method"))
            assertNotNull(sent.obj("params"))
            assertFalse("frame must be a single line", frames[0].contains("\n"))
        } finally {
            closeAll(ws, client, scope, server)
        }
    }

    @Test
    fun errorFrameFailsTheRequestWithTheServersMessage() = runBlocking {
        val ws = servedWebSocket(
            onMessage = { socket, text ->
                val id = Json.parseToJsonElement(text).objOrNull()!!.str("id")!!
                socket.send(
                    """{"jsonrpc":"2.0","id":"$id","error":{"code":4023,"message":"session is still active"}}""",
                )
            },
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        server.start()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(OkHttpClient(), scope)
        try {
            client.connect(wsUrl(server))
            val result = runCatching { client.request("session.delete") }

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue("expected HermesRpcException, got $error", error is HermesRpcException)
            assertEquals(4023, (error as HermesRpcException).code)
            assertEquals("session is still active", error.message)
        } finally {
            closeAll(ws, client, scope, server)
        }
    }

    @Test
    fun idlessServerPushReachesTheEventFlowAsGatewayEvent() = runBlocking {
        val ws = servedWebSocket(
            onOpen = { socket ->
                // Frames are newline-delimited, so the whole event must be one line:
                // HermesClient splits an inbound frame on '\n' and parses each line.
                socket.send(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s-1","seq":7,"payload":{"text":"hi"}}}""",
                )
            },
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        server.start()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(OkHttpClient(), scope)
        try {
            // Subscribe before dialling so the push cannot be missed. UNDISPATCHED
            // runs collect() synchronously up to its first suspension, which registers
            // the subscriber before connect() can receive anything.
            val collected = CompletableDeferred<GatewayEvent>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { collected.complete(it) }
            }

            client.connect(wsUrl(server))

            val event = withTimeout(5_000) { collected.await() }
            assertEquals(GatewayEvent.MESSAGE_DELTA, event.type)
            assertEquals("s-1", event.sessionId)
            assertEquals(7, event.seq)
            assertEquals("hi", event.text)
            collector.cancel()
        } finally {
            closeAll(ws, client, scope, server)
        }
    }

    @Test
    fun unansweredRequestFailsByTimeoutInsteadOfHanging() = runBlocking {
        val frames = CopyOnWriteArrayList<String>()
        val ws = servedWebSocket(onMessage = { _, text -> frames += text })
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(ws))
        server.start()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = HermesClient(OkHttpClient(), scope)
        try {
            client.connect(wsUrl(server))
            val startedAt = System.currentTimeMillis()
            val result = runCatching { client.request("session.list", timeoutMs = 400) }
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertTrue("expected HermesTransportException, got $error", error is HermesTransportException)
            assertTrue(
                "message should mention the timeout: ${error!!.message}",
                error.message!!.contains("timed out", ignoreCase = true),
            )
            assertTrue("must not hang: ${elapsed}ms", elapsed < 5_000)
            assertEquals(1, frames.size)
        } finally {
            closeAll(ws, client, scope, server)
        }
    }

    private fun wsUrl(server: MockWebServer): String =
        server.url("/api/ws").toString().replaceFirst("http:", "ws:")

    /**
     * Closes the socket from both ends before shutting the server down.
     *
     * `MockWebServer.shutdown()` waits for its task queues to go idle, and an
     * upgraded WebSocket keeps one busy until the connection is actually torn
     * down — so the server-side socket has to be closed explicitly.
     */
    private fun closeAll(ws: ServedWebSocket, client: HermesClient, scope: CoroutineScope, server: MockWebServer) {
        ws.socket.get()?.close(1000, null)
        client.disconnect()
        scope.cancel()
        server.shutdown()
    }

    private class ServedWebSocket(
        private val openHandler: (WebSocket) -> Unit = {},
        private val messageHandler: (WebSocket, String) -> Unit = { _, _ -> },
    ) : WebSocketListener() {

        val socket = AtomicReference<WebSocket?>(null)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket.set(webSocket)
            openHandler(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messageHandler(webSocket, text)
        }
    }

    private fun servedWebSocket(
        onOpen: (WebSocket) -> Unit = {},
        onMessage: (WebSocket, String) -> Unit = { _, _ -> },
    ): ServedWebSocket = ServedWebSocket(onOpen, onMessage)
}
