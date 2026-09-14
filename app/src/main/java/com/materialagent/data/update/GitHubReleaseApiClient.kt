package com.materialagent.data.update

import com.materialagent.core.HermesJson
import com.materialagent.update.VersionComparison
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the newest release of this repository from the public GitHub API.
 *
 * The app ships through GitHub Releases only, so a release *is* the update
 * manifest — there is no update server of ours to run and nothing to keep in
 * sync. The API is anonymous, which is why no key exists here to configure or
 * leak.
 */
class GitHubReleaseApiClient(
    private val client: OkHttpClient,
    private val json: Json = HermesJson,
    /** Overridable so the release parsing can be exercised against a stub server. */
    private val releasesUrl: String = RELEASES_URL,
    /**
     * Host policy for the checksum asset. Injected so the resolution can be
     * exercised against a local server; production passes [ReleaseHosts], and the
     * download itself is pinned there regardless of what is passed here.
     */
    private val allowsAssetHost: (String?) -> Boolean = ReleaseHosts::allows,
) {

    /**
     * Fetches the latest release, or fails with a message worth showing someone.
     *
     * A failure is returned as a failure and never converted into "up to date":
     * an offline phone must not look like a fully updated one.
     */
    suspend fun fetchLatest(): Result<AppUpdate> = withContext(Dispatchers.IO) {
        try {
            Result.success(resolveDigest(fetch()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun fetch(): AppUpdate {
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubReleaseException("GitHub returned HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw GitHubReleaseException("GitHub returned an empty release")
            parse(body)
        }
    }

    /**
     * Picks the newest installable release, betas included.
     *
     * This used to read `/releases/latest`, which *excludes pre-releases* — so the
     * moment builds are published as betas, that endpoint reports nothing and the
     * updater silently goes quiet. Listing releases and choosing by version also
     * stops a freshly published beta of an older line outranking a newer stable.
     */
    private fun parse(body: String): AppUpdate {
        val releases = json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        val candidates = releases.mapNotNull { release ->
            val tag = release["tag_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val versionName = tag.removePrefix("v").removePrefix("V")
            if (VersionComparison.versionCode(versionName) <= 0) return@mapNotNull null
            val assets = release["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = assets
                .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return@mapNotNull null
            val assetName = apk["name"]?.jsonPrimitive?.content.orEmpty()
            val downloadUrl = apk["browser_download_url"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // The checksum is published twice: as a `<apk>.sha256` asset, and in
            // the body under `SHA256:`. Both come from the same workflow step, so
            // either is authoritative — the asset is preferred only because it is
            // parseable without reading prose.
            val sha256Url = assets
                .firstOrNull { it["name"]?.jsonPrimitive?.content == "$assetName.sha256" }
                ?.get("browser_download_url")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
            AppUpdate(
                versionName = versionName,
                versionCode = VersionComparison.versionCode(versionName),
                releaseName = release["name"]?.jsonPrimitive?.content.orEmpty(),
                releaseNotes = release["body"]?.jsonPrimitive?.content.orEmpty(),
                downloadUrl = downloadUrl,
                assetName = assetName,
                assetSize = apk["size"]?.jsonPrimitive?.long ?: 0L,
                publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty(),
                isPreRelease = release["prerelease"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                sha256 = Sha256.parse(release["body"]?.jsonPrimitive?.content),
                sha256Url = sha256Url,
            )
        }
        return candidates.maxWithOrNull { a, b ->
            VersionComparison.compare(a.versionName, b.versionName) ?: 0
        } ?: throw GitHubReleaseException("No release with an APK attached was found")
    }

    /**
     * Replaces the digest with the one from the `<apk>.sha256` asset when there
     * is one, leaving the body digest in place as the fallback.
     *
     * A failure here is not fatal: the digest already parsed from the body still
     * verifies the download, and the file is checked against it later either way.
     */
    private fun resolveDigest(update: AppUpdate): AppUpdate {
        val assetUrl = update.sha256Url
        if (assetUrl == null || !allowsAssetHost(assetUrl)) return update
        val fromAsset = runCatching { downloadText(assetUrl) }.getOrNull()?.let(Sha256::parse)
        return if (fromAsset == null) update else update.copy(sha256 = fromAsset)
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubReleaseException("Checksum download failed: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val OWNER = "FammasMaz"
        const val REPO = "MaterialAgent"
        const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    }
}

/** A release that could not be read, worded for the person who will see it. */
private class GitHubReleaseException(message: String) : Exception(message)
