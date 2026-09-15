package com.materialagent.data

import com.materialagent.core.AuthMode
import com.materialagent.core.HermesCredentialsException
import com.materialagent.core.HermesTransportException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a dial failure means, and who has to fix it.
 *
 * Only [HermesCredentialsException] is the user's business. Everything else has to
 * keep retrying: the retry watcher gives up on a rejected credential, and it used to
 * decide that by looking for the words "password", "token" or "sign-in" in the error
 * text. A gateway restarting mid-login answers `HTTP 503` to `/auth/providers`, whose
 * message says "Could not check sign-in options" — so the watcher ended, the app sat
 * on a connection error, and the only way back was the Retry button, which is the
 * failure the watcher exists to prevent.
 */
class ConnectionFailureTest {

    private val noSecrets = object : SecretSource {
        override fun get(id: String): String? = null
    }

    private fun connection() = HermesConnection(
        OkHttpClient(),
        noSecrets,
        CoroutineScope(Dispatchers.Default),
    )

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    /** The same two HTTP steps a password profile performs, against a stub gateway. */
    private fun stubPasswordHandshake(loginCode: Int) {
        server.enqueue(MockResponse().setBody("""{"providers":[{"name":"basic","supports_password":true}]}"""))
        server.enqueue(MockResponse().setResponseCode(loginCode))
    }

    private fun againstStub() = HermesConnection(
        OkHttpClient(),
        object : SecretSource {
            override fun get(id: String): String? = "a stored password"
        },
        CoroutineScope(Dispatchers.Default),
    )

    private fun stubProfile() = ServerProfile(
        id = "p2",
        name = "Home",
        baseUrl = server.url("/").toString().removeSuffix("/"),
        authMode = AuthMode.PASSWORD,
        username = "fammasmaz",
    )

    private val home = ServerProfile(
        id = "p1",
        name = "Home",
        baseUrl = "http://box:9119",
        authMode = AuthMode.PASSWORD,
        username = "fammasmaz",
    )

    @Test
    fun aTransientSignInProblemIsRetriedRatherThanBlamedOnTheUser() {
        val status = connection().classify(
            home,
            HermesTransportException("Could not check sign-in options at http://box:9119 (HTTP 503)."),
        )
        assertFalse((status as ConnectionStatus.Failed).needsCredentials)
        assertTrue(status.message.contains("503"))
    }

    @Test
    fun aGatewayThatFailsSignInMidRestartKeepsRetrying() {
        val status = connection().classify(
            home,
            HermesTransportException("Sign-in failed (HTTP 502)."),
        )
        assertFalse((status as ConnectionStatus.Failed).needsCredentials)
    }

    @Test
    fun aRejectedPasswordAsksTheUserForOne() {
        val status = connection().classify(
            home,
            HermesCredentialsException("Wrong username or password for Home."),
        )
        assertTrue((status as ConnectionStatus.Failed).needsCredentials)
        assertEquals("Wrong username or password for Home.", status.message)
    }

    @Test
    fun aMissingStoredSecretAsksTheUserForOne() {
        val status = connection().classify(
            home,
            HermesCredentialsException("No password stored for Home."),
        )
        assertTrue((status as ConnectionStatus.Failed).needsCredentials)
    }

    @Test
    fun aSlowGatewayIsNotAReasonToAskForCredentials() {
        val status = connection().classify(home, SocketTimeoutException("timeout"))
        assertFalse((status as ConnectionStatus.Failed).needsCredentials)
        assertTrue(status.message.contains("did not answer in time"))
    }

    @Test
    fun aCancelledAttemptIsNotAFailureAtAll() {
        assertEquals(ConnectionStatus.Idle, connection().classify(home, CancellationException("left the screen")))
    }

    @Test
    fun aGatewayThatAnswers503ToSignInDoesNotAskForCredentials() = runBlocking {
        stubPasswordHandshake(503)
        val failure = againstStub().activate(stubProfile()) as ConnectionStatus.Failed
        assertFalse(failure.needsCredentials)
        assertEquals("Sign-in failed (HTTP 503).", failure.message)
    }

    @Test
    fun aWrongPasswordDoesAskForCredentials() = runBlocking {
        stubPasswordHandshake(401)
        val failure = againstStub().activate(stubProfile()) as ConnectionStatus.Failed
        assertTrue(failure.needsCredentials)
        assertEquals("Wrong username or password for Home.", failure.message)
    }
}
