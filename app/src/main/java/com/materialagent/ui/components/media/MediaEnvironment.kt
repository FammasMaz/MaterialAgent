package com.materialagent.ui.components.media

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import okhttp3.OkHttpClient

/**
 * Everything a media row needs to fetch its file.
 *
 * Attachment rendering is deep in the transcript, several composables below the
 * chat screen, and threading a server address and an HTTP client down through
 * every row renderer would put transport concerns in the signatures of
 * components that only draw text today. So the *screen* — the composition root
 * for this subtree — builds this once and publishes it, and the rows read it:
 * construction stays at the boundary, exactly as the container does for
 * ViewModels, while the rows themselves still build nothing.
 *
 * A null environment is a real state (no active profile), and rows render
 * nothing in it rather than inventing a URL that cannot be fetched.
 */
class MediaEnvironment(
    /**
     * The signed-in server, without a trailing slash. Media URLs are derived from
     * it by [com.materialagent.core.model.MediaUrls].
     */
    val baseUrl: String?,
    /**
     * The app's one OkHttp client, shared with the connection layer. It is not
     * optional and must never be replaced by a fresh client: its cookie jar is
     * what carries the password sign-in, and the gateway answers 401 without it.
     */
    val client: OkHttpClient,
    /** Where downloads land before an intent hands them to another app. */
    val cacheDir: File?,
) {

    /**
     * Decoded thumbnails, shared by every image row in the process.
     *
     * A transcript is a list, so the same picture can be re-composed many times
     * as it scrolls in and out of the viewport; without a shared cache each
     * return trip pays a network round-trip plus a decode. The budget is heap,
     * not entries, and it is deliberately a fraction of what a phone gives a
     * small app: a decoded ARGB bitmap costs width × height × 4 bytes, so this
     * holds a handful of screen-sized images and refuses to grow past that.
     */
    val images: BoundedCache<ImageBitmap> = BoundedCache(MAX_IMAGE_BYTES) { bitmap ->
        // Sampled on purpose (see ImageSampleSize): the value is the real
        // allocation, so the budget tracks heap rather than the compression of
        // whatever JPEG happened to arrive.
        bitmap.asAndroidBitmap().let { android ->
            if (android.config == Bitmap.Config.RGB_565) {
                android.byteCount.toLong()
            } else {
                android.allocationByteCount.toLong()
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 12L * 1024 * 1024
    }
}

val LocalMediaEnvironment = staticCompositionLocalOf<MediaEnvironment?> { null }

/**
 * Builds the environment for one server, or null when there is none.
 *
 * Rebuilt when the address changes — a reconnect to a different host must not
 * keep serving pictures fetched from the previous one, and the OkHttp jar's
 * cookies are host-scoped anyway, so stale thumbnails would 401 on the next
 * fetch rather than render.
 */
@Composable
fun rememberMediaEnvironment(baseUrl: String?, client: OkHttpClient): MediaEnvironment? {
    val context = LocalContext.current
    return remember(baseUrl, client, context) {
        if (baseUrl.isNullOrBlank()) {
            null
        } else {
            MediaEnvironment(
                baseUrl = baseUrl,
                client = client,
                cacheDir = context.cacheDir,
            )
        }
    }
}
