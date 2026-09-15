package com.materialagent.data

import com.materialagent.core.AuthMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The gateway version is a label rather than part of the handshake: Hermes sends no
 * version in `gateway.ready`, so [HermesConnection] reads `GET /api/health` instead.
 *
 * The invariants pinned here: the number comes from `version`, anything unreadable is
 * null rather than a broken string, and a server that refuses the request costs the
 * connection nothing.
 */
class ServerVersionTest {

    private lateinit var server: MockWebServer

    private val noSecrets = object : SecretSource {
        override fun get(id: String): String? = null
    }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun connection() = HermesConnection(
        OkHttpClient(),
        noSecrets,
        CoroutineScope(Dispatchers.Default),
    )

    private fun profile() = ServerProfile(
        id = "p1",
        name = "Test gateway",
        baseUrl = server.url("/").toString().removeSuffix("/"),
        authMode = AuthMode.TOKEN,
    )

    @Test
    fun theVersionIsReadFromTheHealthPayload() {
        val body = """{"ok":true,"version":"0.21.0","auth_required":true}"""
        assertEquals("0.21.0", connection().healthVersion(body))
    }

    @Test
    fun aPayloadWithoutAVersionYieldsNothing() {
        assertNull(connection().healthVersion("""{"ok":true}"""))
        assertNull(connection().healthVersion("""{"version":"   "}"""))
        assertNull(connection().healthVersion("not json at all"))
        assertNull(connection().healthVersion(""))
    }

    @Test
    fun theProbeAsksHealthAndKeepsTheNumber() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"version":"0.21.0"}"""))
        assertEquals("0.21.0", connection().fetchServerVersion(profile()))
        assertEquals("/api/health", server.takeRequest().path)
    }

    @Test
    fun aRefusedProbeIsQuietlyNothing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(connection().fetchServerVersion(profile()))
    }

    @Test
    fun anUnreachableServerIsQuietlyNothing() = runBlocking {
        val dead = ServerProfile(
            id = "p2",
            name = "Gone",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            authMode = AuthMode.TOKEN,
        )
        server.shutdown()
        assertNull(connection().fetchServerVersion(dead))
    }
}
