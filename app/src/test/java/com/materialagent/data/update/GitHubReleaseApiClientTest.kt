package com.materialagent.data.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [GitHubReleaseApiClient] against a stub release API.
 *
 * The invariants: the digest published as a `<apk>.sha256` asset is the one the
 * update carries, the digest quoted in the release body stands in for it when the
 * asset is missing, unreachable, or off a host the app will not fetch from, and
 * a release that publishes neither is still returned — with a null digest, which
 * is what makes the download refuse it later rather than install it blind.
 */
class GitHubReleaseApiClientTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()

    private val assetDigest = "a".repeat(64)
    private val bodyDigest = "b".repeat(64)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun prefersTheDigestFromThePublishedChecksumAsset() = runBlocking {
        enqueueRelease(
            digestAssetUrl = { base -> "$base/app.apk.sha256" },
            body = "Fixed things. SHA256: $bodyDigest",
        )
        server.enqueue(MockResponse().setBody("$assetDigest  MaterialAgent-1.0.0-beta.9-signed.apk\n"))

        val update = client(permissiveAssetHost = true).fetchLatest().getOrThrow()

        assertEquals(assetDigest, update.sha256)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fallsBackToTheBodyDigestWhenTheAssetIsNotOnAGitHubHost() = runBlocking {
        // The default host policy refuses the stub server, so the checksum asset
        // is never fetched and the digest from the release notes is used.
        enqueueRelease(
            digestAssetUrl = { base -> "$base/app.apk.sha256" },
            body = "Fixed things. SHA256: $bodyDigest",
        )

        val update = client().fetchLatest().getOrThrow()

        assertEquals(bodyDigest, update.sha256)
        assertEquals("nothing else should have been fetched", 1, server.requestCount)
    }

    @Test
    fun fallsBackToTheBodyDigestWhenTheAssetCannotBeRead() = runBlocking {
        enqueueRelease(
            digestAssetUrl = { base -> "$base/app.apk.sha256" },
            body = "Fixed things. SHA256: $bodyDigest",
        )
        server.enqueue(MockResponse().setResponseCode(503))

        val update = client(permissiveAssetHost = true).fetchLatest().getOrThrow()

        assertEquals(bodyDigest, update.sha256)
    }

    @Test
    fun reportsNoDigestWhenTheReleasePublishesNone() = runBlocking {
        enqueueRelease(digestAssetUrl = null, body = "Fixed things, checksum forgotten.")

        val update = client().fetchLatest().getOrThrow()

        assertNull(update.sha256)
        // The release is still reported: refusing it is the download's job, and
        // the version comparison above it has already run.
        assertEquals("1.0.0-beta.9", update.versionName)
    }

    @Test
    fun readsTheApkAssetAndItsVersionFromTheRelease() = runBlocking {
        enqueueRelease(digestAssetUrl = null, body = "")

        val update = client().fetchLatest().getOrThrow()

        assertEquals("1.0.0-beta.9", update.versionName)
        assertEquals("MaterialAgent-1.0.0-beta.9-signed.apk", update.assetName)
        assertEquals(1234L, update.assetSize)
        assertTrue(update.isPreRelease)
        assertTrue(update.downloadUrl.endsWith("/app.apk"))
    }

    @Test
    fun offersTheNewestReleaseRatherThanTheFirstOneListed() = runBlocking {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(tag = "v1.0.0-beta.7", apkUrl = "$base/old.apk"),
                    release(tag = "v1.0.0-beta.9", apkUrl = "$base/new.apk"),
                ),
            ),
        )

        val update = client().fetchLatest().getOrThrow()

        assertEquals("1.0.0-beta.9", update.versionName)
        assertTrue(update.downloadUrl.endsWith("/new.apk"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun client(permissiveAssetHost: Boolean = false) = GitHubReleaseApiClient(
        client = client,
        releasesUrl = server.url("/releases").toString(),
        allowsAssetHost = if (permissiveAssetHost) { _ -> true } else ReleaseHosts::allows,
    )

    /** Queues one release whose asset URLs point back at this stub server. */
    private fun enqueueRelease(digestAssetUrl: ((String) -> String?)?, body: String) {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(
                        tag = "v1.0.0-beta.9",
                        apkUrl = "$base/app.apk",
                        digestUrl = digestAssetUrl?.invoke(base),
                        body = body,
                    ),
                ),
            ),
        )
    }

    private fun releasesJson(vararg releases: String) = releases.joinToString(",", "[", "]")

    private fun release(
        tag: String,
        apkUrl: String,
        digestUrl: String? = null,
        body: String = "",
    ): String {
        val version = tag.removePrefix("v")
        val name = "MaterialAgent-$version-signed.apk"
        val digestAsset = if (digestUrl == null) {
            ""
        } else {
            """,{"name":"$name.sha256","size":96,"browser_download_url":"$digestUrl"}"""
        }
        return """
        {
          "tag_name": "$tag",
          "name": "MaterialAgent $tag",
          "body": "$body",
          "prerelease": true,
          "published_at": "2025-01-01T00:00:00Z",
          "assets": [
            {"name":"$name","size":1234,"browser_download_url":"$apkUrl"}
            $digestAsset
          ]
        }
        """.trimIndent()
    }
}