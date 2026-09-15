package com.materialagent.ui.components.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.materialagent.core.model.MediaRef
import com.materialagent.data.HapticCue
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.ExpressiveMotion
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/*
 * An image attachment: a thumbnail in the transcript, and the whole picture plus
 * its caption on a tap.
 *
 * There is no image-loading library in this app, so the fetch, the decode and
 * the cache are here. That is survivable precisely because the gateway hands
 * images back as base64 data URLs: there is no streaming, no placeholder
 * protocol and no format negotiation to reimplement — one request, one decode,
 * one bitmap.
 */

/** What an image row is showing right now. */
private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val image: ImageBitmap) : ImageState
    data class Failed(val message: String) : ImageState
}

/** Longest edge a decoded thumbnail keeps; see [ImageSampleSize]. */
private const val THUMBNAIL_MAX_DIMENSION = 1600

/** How far a thumbnail's shape may drift from the real one before it is cropped. */
private const val MIN_ASPECT = 0.5f
private const val MAX_ASPECT = 2.0f

/**
 * A tap-to-enlarge image.
 *
 * Loads from the shared cache first, so scrolling back to a picture costs
 * nothing, and decodes off the main thread — a 4000px photo is tens of
 * milliseconds of work that would otherwise land as a dropped frame in the
 * middle of a scroll.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageAttachment(
    ref: MediaRef,
    url: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val env = LocalMediaEnvironment.current ?: return
    val cue = rememberCue()

    var attempt by remember(url) { mutableIntStateOf(0) }
    var state by remember(url) { mutableStateOf<ImageState>(ImageState.Loading) }
    var viewerOpen by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, env, attempt) {
        state = loadImage(env, url)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The picture fades in rather than appearing: a full-width bitmap landing
        // in one frame mid-scroll reads as a layout jump.
        val imageAlpha by animateFloatAsState(
            targetValue = if (state is ImageState.Ready) 1f else 0f,
            // The app's effects spec: an alpha that overshoots reads as a flicker,
            // so this is deliberately not one of the spatial springs.
            animationSpec = ExpressiveMotion.Specs.alpha,
            label = "imageAlpha",
        )
        when (val current = state) {
            is ImageState.Ready -> Image(
                bitmap = current.image,
                contentDescription = caption ?: ref.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    // The thumbnail's height follows the picture, but only within
                    // limits: an unclamped 0.3-ratio screenshot would be taller
                    // than the screen and a panorama would be a hairline.
                    .aspectRatio(
                        (current.image.width.toFloat() / current.image.height)
                            .coerceIn(MIN_ASPECT, MAX_ASPECT),
                    )
                    .graphicsLayer { this.alpha = imageAlpha }
                    .clip(MaterialTheme.shapes.large)
                    .clickable {
                        cue(HapticCue.UI_ACTION)
                        viewerOpen = true
                    },
            )

            is ImageState.Failed -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.Image, contentDescription = null)
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { attempt++ }, shapes = ButtonDefaults.shapes()) {
                        Text("Try again")
                    }
                }
            }

            ImageState.Loading -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        caption?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (viewerOpen) {
        (state as? ImageState.Ready)?.let { ready ->
            ImageViewer(
                image = ready.image,
                caption = caption ?: ref.name,
                onDismiss = { viewerOpen = false },
            )
        }
    }
}

/**
 * The full-screen view: the picture as large as it fits, its caption underneath.
 *
 * A dialog without the platform's default width inset, so it really does cover
 * the screen instead of floating in a card.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImageViewer(
    image: ImageBitmap,
    caption: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Near-black rather than the theme's background: a picture is
                // judged against a neutral void, and it works in both themes.
                .background(Color.Black.copy(alpha = 0.96f))
                .clickable(onClick = onDismiss),
        ) {
            Image(
                bitmap = image,
                contentDescription = caption,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                caption?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }
    }
}

/** Fetches, decodes and caches one thumbnail, off the main thread. */
private suspend fun loadImage(env: MediaEnvironment, url: String): ImageState {
    env.images.get(url)?.let { return ImageState.Ready(it) }

    return try {
        val decoded = withContext(Dispatchers.IO) {
            // Straight through the shared client: its cookie jar is what
            // authenticates the request, and no explicit header is needed.
            val request = Request.Builder().url(url).build()
            env.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        if (response.code == 401 || response.code == 403) {
                            "The server refused this image."
                        } else {
                            "The server answered HTTP ${response.code}."
                        },
                    )
                }
                val body = response.body?.string()
                    ?: throw IOException("The server sent an empty reply.")
                val bytes = DataUrlFormat.imageBytes(body)
                    ?: throw IOException("The reply held no image data.")
                decodeSampled(bytes, THUMBNAIL_MAX_DIMENSION)
                    ?: throw IOException("This image could not be decoded.")
            }
        }
        val image = decoded.asImageBitmap()
        env.images.put(url, image)
        ImageState.Ready(image)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ImageState.Failed(error.message ?: "The image could not be loaded.")
    }
}

/**
 * Decodes at a bounded size: bounds first (which allocates nothing), then a
 * sample factor, then the pixels. Two passes over the compressed bytes is far
 * cheaper than one pass that allocates 40 MB.
 */
private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageSampleSize.forBounds(bounds.outWidth, bounds.outHeight, maxDimension)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
