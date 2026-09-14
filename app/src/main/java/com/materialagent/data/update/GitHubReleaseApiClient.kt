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
) {

    /**
     * Fetches the latest release, or fails with a message worth showing someone.
     *
     * A failure is returned as a failure and never converted into "up to date":
     * an offline phone must not look like a fully updated one.
     */
    suspend fun fetchLatest(): Result<AppUpdate> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun fetch(): AppUpdate {
        val request = Request.Builder()
            .url(RELEASES_URL)
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
            val apk = release["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return@mapNotNull null
            val downloadUrl = apk["browser_download_url"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            AppUpdate(
                versionName = versionName,
                versionCode = VersionComparison.versionCode(versionName),
                releaseName = release["name"]?.jsonPrimitive?.content.orEmpty(),
                releaseNotes = release["body"]?.jsonPrimitive?.content.orEmpty(),
                downloadUrl = downloadUrl,
                assetName = apk["name"]?.jsonPrimitive?.content.orEmpty(),
                assetSize = apk["size"]?.jsonPrimitive?.long ?: 0L,
                publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty(),
                isPreRelease = release["prerelease"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            )
        }
        return candidates.maxWithOrNull { a, b ->
            VersionComparison.compare(a.versionName, b.versionName) ?: 0
        } ?: throw GitHubReleaseException("No release with an APK attached was found")
    }

    private companion object {
        const val OWNER = "FammasMaz"
        const val REPO = "MaterialAgent"
        const val RELEASES_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    }
}

/** A release that could not be read, worded for the person who will see it. */
private class GitHubReleaseException(message: String) : Exception(message)
