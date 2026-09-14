package com.materialagent.ui.components.media

import com.materialagent.core.HermesJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * The non-UI half of media rendering: how a request is authenticated, how the
 * gateway's answer is turned into bytes, and the small formatting rules the
 * components share.
 *
 * All of it is plain Kotlin over OkHttp, with no Android types, so the parts
 * that are easy to get wrong — the cookie the gateway demands, the base64 data
 * URL it returns, the downsampling factor an image decode uses — are pinned by
 * JVM tests instead of by looking at the screen. What remains Android-only is
 * file I/O into the cache and the intents that hand a file to another app
 * (see MediaDownloads.kt).
 */

/**
 * The credential every media request needs.
 *
 * The gateway gates its own routes: an unauthenticated `/api/media` answers 401,
 * and the old `?token=` query parameter is gone, so the *only* thing that opens
 * a file is the session cookie the password sign-in left behind. That cookie
 * lives in the app's shared OkHttp client jar ([com.materialagent.data.Http]),
 * which is why every fetch here is handed that client rather than a fresh one —
 * OkHttp attaches the cookie from the jar by itself.
 *
 * MediaPlayer is the one exception: it fetches on its own, outside OkHttp, so it
 * has to be told the cookie as an explicit header.
 */
object MediaAuth {

    const val COOKIE_HEADER = "Cookie"

    /** `name=value; name2=value2`, or null when the jar has nothing for this host. */
    fun cookieHeader(jar: CookieJar, url: String): String? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val cookies = jar.loadForRequest(httpUrl)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * The headers to hand [android.media.MediaPlayer] for [url].
     *
     * An empty map is not the same as null here: MediaPlayer's three-argument
     * `setDataSource` is the only overload that carries HTTP headers at all, and
     * it accepts a map with nothing in it.
     */
    fun streamHeaders(client: OkHttpClient, url: String): Map<String, String> {
        val cookie = cookieHeader(client.cookieJar, url) ?: return emptyMap()
        return mapOf(COOKIE_HEADER to cookie)
    }
}

/**
 * Unwraps `GET /api/media`'s reply: `{"data_url": "data:image/png;base64,…"}`.
 *
 * The gateway inlines images rather than streaming them, so the bytes arrive as
 * a data URL inside JSON. Both halves are tolerant on purpose — an unexpected
 * shape returns null so the row can report "could not load" instead of throwing
 * somewhere up the compose tree.
 */
object DataUrlFormat {

    /** Decodes the image bytes from the endpoint's JSON body, or null. */
    fun imageBytes(body: String): ByteArray? {
        val dataUrl = runCatching {
            HermesJson.parseToJsonElement(body).jsonObject["data_url"]?.jsonPrimitive?.content
        }.getOrNull() ?: return null
        return decode(dataUrl)
    }

    /** Decodes one `data:<mime>;base64,<payload>` URL, or null if it is not that. */
    fun decode(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        // The metadata section is what says how the payload is encoded; a plain
        // (unencoded) data URL is not something this endpoint can produce, so
        // refusing it is better than handing back the literal text as bytes.
        if (!dataUrl.substring(0, comma).contains("base64", ignoreCase = true)) return null
        val payload = dataUrl.substring(comma + 1)
        if (payload.isBlank()) return null
        // The MIME decoder tolerates the line breaks some encoders insert; plain
        // base64 is a strict subset of what it accepts.
        return runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
    }
}

/** Streams a downloaded file to disk inside the app's cache. */
object MediaDownload {

    /**
     * Fetches [url] into [target] with the authenticated [client], returning the
     * number of bytes written.
     *
     * A failed or interrupted transfer deletes whatever landed: a half-written
     * file in the cache is worse than none, because the open/share intent would
     * happily hand a truncated PDF to a viewer and the user would see corruption
     * rather than an error.
     */
    fun fetch(client: OkHttpClient, url: String, target: File): Long {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        if (response.code == 401 || response.code == 403) {
                            "The server refused this file. Reconciling the sign-in may fix it."
                        } else {
                            "The server answered HTTP ${response.code}."
                        },
                    )
                }
                val body = response.body ?: throw IOException("The server sent no file body.")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_BYTES)
                    }
                }
            }
            return target.length()
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private const val DEFAULT_BUFFER_BYTES = 64 * 1024
}

/**
 * Names a downloaded file may be written under.
 *
 * The attachment name comes from a path the *agent* chose, so it can contain
 * separators, quotes or a `..` — this strips anything a filesystem or a
 * subsequent intent would rather not receive, and never returns a name that
 * would resolve to a parent directory.
 */
object MediaNames {

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    private const val MAX_LENGTH = 120

    fun safe(name: String): String {
        val cleaned = name
            .replace(UNSAFE, "_")
            // Leading dots hide the file on unix and trailing dots are illegal on
            // windows; both also let "." and ".." survive as path structure.
            .trim('.', '_', ' ')
            .take(MAX_LENGTH)
        return cleaned.ifBlank { "attachment" }
    }
}

/** `0:07`, `4:31`, `1:02:03` — how a player labels a position. */
object MediaTime {

    fun format(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L)) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0L) {
            // Locale.US because this is a numeric token, not prose: some locales
            // would render it in non-ASCII digits, which reads as a glitch here.
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, rest)
        }
    }
}

/** A short, human size for a file row: `812 B`, `47 kB`, `3.4 MB`. */
fun formatMediaSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.0f kB".format(Locale.US, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * The `inSampleSize` an image decode should use.
 *
 * A phone camera photo is 4000px wide and a few megabytes compressed; decoded
 * whole, one of them is more than 40 MB of heap, and a transcript may hold
 * several. Sampling down to the width the screen can actually show is the
 * difference between a picture that renders and an OutOfMemoryError, so this
 * runs *before* the real decode, using bounds-only decoding to learn the size
 * without allocating the pixels.
 */
object ImageSampleSize {

    fun forBounds(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        // Powers of two only: BitmapFactory rounds anything else down, so asking
        // for 3 would silently deliver 2 and the arithmetic here would be a lie.
        while (
            width / (sample * 2) >= maxDimension ||
            height / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
        return sample
    }
}
