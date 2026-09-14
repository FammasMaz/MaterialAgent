package com.materialagent.ui.components.media

import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.MediaRef
import com.materialagent.core.model.MediaUrls
import com.materialagent.data.MemoryCookieJar
import java.io.File
import java.io.IOException
import java.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards the non-UI half of media rendering.
 *
 * The part worth pinning down is authentication: the gateway's file endpoints
 * answer 401 unless the request carries the session cookie the password sign-in
 * left in the app's shared client jar, and the old `?token=` query is refused
 * outright. A device is needed to watch a recording play, but *that the request
 * is authenticated* is provable here, and it is the failure that would otherwise
 * look like "audio is broken".
 */
class MediaFetchTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ── authentication ──────────────────────────────────────────────────────

    /**
     * The whole design in one test: sign in on a client with a cookie jar, then
     * fetch media with that same client and prove the cookie travelled.
     *
     * This is why the UI is handed [com.materialagent.data.Http.client] instead
     * of building its own — OkHttp attaches the jar's cookies by itself, so
     * nothing in the media code has to remember to pass a credential.
     */
    @Test
    fun aMediaFetchThroughTheSharedClientCarriesTheSignInCookie() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().addHeader("Set-Cookie", "hermes_session=s3cret; Path=/").setBody("{}"),
        )
        server.enqueue(MockResponse().setBody("audio-bytes"))
        server.start()

        val client = OkHttpClient.Builder().cookieJar(MemoryCookieJar()).build()
        val base = server.url("/").toString().trimEnd('/')
        val ref = MediaRef(path = "/tmp/voice note.m4a", caption = null, kind = MediaKind.AUDIO)

        try {
            client.newCall(
                Request.Builder()
                    .url("$base/auth/password-login")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().close()

            val target = File(temp.newFolder(), "voice.m4a")
            val written = MediaDownload.fetch(client, MediaUrls.forAudio(base, ref), target)

            server.takeRequest()
            val media = server.takeRequest()
            assertEquals(
                "the media request must carry the session cookie",
                "hermes_session=s3cret",
                media.getHeader("Cookie"),
            )
            // The URL the UI would build, path encoded as one query parameter.
            assertEquals(
                "/api/files/stream?path=%2Ftmp%2Fvoice%20note.m4a",
                media.path,
            )
            assertEquals("audio-bytes".length.toLong(), written)
            assertEquals("audio-bytes", target.readText())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun cookieHeaderJoinsEveryCookieTheJarHasForTheHost() {
        val url = "http://host:9119/api/files/stream".toHttpUrl()
        val jar = FixedJar(
            listOf(
                Cookie.parse(url, "a=1")!!,
                Cookie.parse(url, "b=2")!!,
            ),
        )

        assertEquals("a=1; b=2", MediaAuth.cookieHeader(jar, url.toString()))
    }

    @Test
    fun cookieHeaderIsNullWhenThereIsNothingToSend() {
        // OkHttp's own empty jar, so this is the real "signed out" case.
        assertNull(MediaAuth.cookieHeader(OkHttpClient().cookieJar, "http://host:9119/api/media"))
        assertTrue(MediaAuth.streamHeaders(OkHttpClient(), "http://host:9119/api/media").isEmpty())
    }

    @Test
    fun streamHeadersCarryTheCookieForMediaPlayer() {
        val url = "http://host:9119/api/files/stream?path=%2Ftmp%2Fa.mp3".toHttpUrl()
        val jar = FixedJar(listOf(Cookie.parse(url, "hermes_session=abc")!!))
        val client = OkHttpClient.Builder().cookieJar(jar).build()

        val headers = MediaAuth.streamHeaders(client, url.toString())

        // MediaPlayer fetches outside OkHttp, so this header *is* the credential.
        assertEquals(mapOf("Cookie" to "hermes_session=abc"), headers)
    }

    // ── the gateway's reply ─────────────────────────────────────────────────

    @Test
    fun anImageComesBackOutOfTheDataUrlReply() {
        val payload = Base64.getEncoder().encodeToString("pixels".toByteArray())
        val body = """{"data_url":"data:image/png;base64,$payload"}"""

        assertEquals("pixels", String(DataUrlFormat.imageBytes(body)!!))
    }

    @Test
    fun aDataUrlThatIsNotBase64IsRefused() {
        assertNull(DataUrlFormat.decode("data:image/png,plain-text"))
        assertNull(DataUrlFormat.decode("data:image/png;base64"))
        assertNull(DataUrlFormat.decode("data:image/png;base64,   "))
    }

    @Test
    fun anUnexpectedReplyShapeIsRefusedRatherThanThrown() {
        assertNull(DataUrlFormat.imageBytes("""{"error":"nope"}"""))
        assertNull(DataUrlFormat.imageBytes("not json at all"))
    }

    // ── downloads ───────────────────────────────────────────────────────────

    @Test
    fun aRefusedDownloadReportsWhyAndLeavesNoFileBehind() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        server.start()
        val target = File(temp.newFolder(), "report.pdf")

        try {
            val failure = assertThrows(IOException::class.java) {
                MediaDownload.fetch(
                    OkHttpClient(),
                    server.url("/api/files/download?path=%2Ftmp%2Freport.pdf").toString(),
                    target,
                )
            }
            assertTrue(
                "the row should say the sign-in is the problem: ${failure.message}",
                failure.message!!.contains("refused"),
            )
            assertFalse("a rejected transfer must not leave a file", target.exists())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aFailedTransferDeletesWhateverLanded() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"gone"}"""))
        server.start()
        val target = File(temp.newFolder(), "clip.mp4")
        // Left over from an earlier attempt: a stale file must not be handed to a
        // viewer as if it were the file the agent just sent.
        target.writeText("half of an older download")

        try {
            assertThrows(IOException::class.java) {
                MediaDownload.fetch(
                    OkHttpClient(),
                    server.url("/api/files/download?path=%2Ftmp%2Fclip.mp4").toString(),
                    target,
                )
            }
            assertFalse(target.exists())
        } finally {
            server.shutdown()
        }
    }

    // ── formatting and naming ───────────────────────────────────────────────

    @Test
    fun aFileNameFromAPathTheAgentChoseIsMadeSafe() {
        assertEquals("report.pdf", MediaNames.safe("report.pdf"))
        assertEquals("etc_passwd", MediaNames.safe("../../etc/passwd"))
        assertEquals("env", MediaNames.safe(".env"))
        assertEquals("attachment", MediaNames.safe(".."))
        assertEquals("attachment", MediaNames.safe(""))
        assertEquals("a_b.png", MediaNames.safe("a b.png"))

        val hostile = MediaNames.safe("../../evil/../x;rm -rf.pdf")
        assertFalse("no separators may survive", hostile.contains('/'))
        assertFalse("the name must not climb out of the cache", hostile.startsWith("."))
    }

    @Test
    fun playbackPositionsReadAsClocks() {
        assertEquals("0:00", MediaTime.format(0))
        assertEquals("0:07", MediaTime.format(7_400))
        assertEquals("1:01", MediaTime.format(61_000))
        assertEquals("1:02:03", MediaTime.format(3_723_000))
        // A stream can report a negative duration before its headers arrive.
        assertEquals("0:00", MediaTime.format(-1))
    }

    @Test
    fun fileSizesReadAsSizes() {
        assertEquals("812 B", formatMediaSize(812))
        assertEquals("46 kB", formatMediaSize(47_000))
        assertEquals("3.4 MB", formatMediaSize(3_600_000))
    }

    @Test
    fun aHugeImageIsDecodedDownToSomethingAScreenCanShow() {
        assertEquals(1, ImageSampleSize.forBounds(1600, 1200, 1600))
        assertEquals(1, ImageSampleSize.forBounds(100, 100, 1600))
        assertEquals(2, ImageSampleSize.forBounds(4000, 3000, 1600))
        assertEquals(4, ImageSampleSize.forBounds(8000, 6000, 1600))
        // Bounds that never decoded are not a reason to downsample.
        assertEquals(1, ImageSampleSize.forBounds(0, 0, 1600))
    }

    /** A jar that always answers with the same cookies. */
    private class FixedJar(private val cookies: List<Cookie>) : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies
    }
}
