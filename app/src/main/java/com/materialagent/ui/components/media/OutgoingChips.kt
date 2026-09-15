package com.materialagent.ui.components.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.OutgoingAttachment
import com.materialagent.core.model.StoredRef
import com.materialagent.ui.theme.AgentShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The files the *user* attached, as opposed to the ones the agent sent.
 *
 * They appear in two places and have to read as the same object in both: in the
 * pending bubble above the composer, where each one is still removable, and
 * inside the sent turn's own bubble, where it is a record of what went.
 *
 * Both surfaces therefore take their colours from [LocalContentColor] rather
 * than naming a container role. The pending strip sits on the app's surface and
 * the sent one sits inside a `primaryContainer` bubble; a fixed role would look
 * correct in one and muddy in the other, and "a slightly raised version of
 * whatever is behind me" is exactly what an inset chip is.
 *
 * Images are decoded from the picker's `content://` URI directly. There is no
 * image library in this app, and no need for one: a thumbnail is one bounds
 * decode plus one sampled decode, and the full bytes are only read when the
 * turn is actually sent.
 */

/** Longest edge a preview thumbnail keeps. */
private const val THUMBNAIL_MAX_DIMENSION = 512

private val THUMBNAIL_SIZE = 84.dp

/**
 * Every file attached to one turn, in the order the user added them.
 *
 * Split by shape rather than by kind: pictures are a scroller of thumbnails,
 * everything else is a stacked list of chips with a name to read. A single
 * column of mixed rows would make three photos take three rows of height and
 * cost the transcript a screenful for nothing.
 */
@Composable
fun OutgoingAttachmentStrip(
    attachments: List<OutgoingAttachment>,
    onRemove: ((OutgoingAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.kind == MediaKind.IMAGE }
    val others = attachments.filter { it.kind != MediaKind.IMAGE }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { attachment ->
                    OutgoingImageThumb(attachment = attachment, onRemove = onRemove)
                }
            }
        }
        others.forEach { attachment ->
            OutgoingFileChip(attachment = attachment, onRemove = onRemove)
        }
    }
}

/**
 * A picture the user is about to send, or has sent.
 *
 * The remove control is an overlay rather than a label beside it, because the
 * thumbnail is the only thing identifying which photo it is — putting the "x" in
 * the corner is what every photo picker does, and it keeps the tap target on the
 * picture it removes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OutgoingImageThumb(
    attachment: OutgoingAttachment,
    onRemove: ((OutgoingAttachment) -> Unit)?,
) {
    val context = LocalContext.current
    var bitmap by remember(attachment.uri) { mutableStateOf<ImageBitmap?>(null) }
    var unreadable by remember(attachment.uri) { mutableStateOf(false) }

    LaunchedEffect(attachment.uri) {
        val decoded = withContext(Dispatchers.IO) {
            decodeThumbnail(context, attachment.uri)
        }
        bitmap = decoded
        unreadable = decoded == null
    }

    Box(modifier = Modifier.size(THUMBNAIL_SIZE)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = LocalContentColor.current.copy(alpha = 0.10f),
            contentColor = LocalContentColor.current,
            modifier = Modifier.fillMaxSize(),
        ) {
            val image = bitmap
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                unreadable -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(
                        Icons.Rounded.BrokenImage,
                        contentDescription = "This picture could not be read",
                        modifier = Modifier.size(28.dp),
                    )
                }

                else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(
                        Icons.Rounded.Image,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        if (onRemove != null) {
            FilledTonalIconButton(
                onClick = { onRemove(attachment) },
                shapes = IconButtonDefaults.shapes(),
                // 28dp is the visual; the 48dp M3 asks for is reserved around it.
                // `minimumInteractiveComponentSize()` centres the smaller button
                // inside itself, so the disc sits 14dp in from the chip's corner
                // rather than 4dp — and its target spans 4dp to 52dp, which is the
                // part that matters for the control that deletes an attachment.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .minimumInteractiveComponentSize()
                    .size(28.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove ${attachment.name}",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** A sound clip: named by kind, because a filename says less than "audio" here. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OutgoingFileChip(
    attachment: OutgoingAttachment,
    onRemove: ((OutgoingAttachment) -> Unit)?,
) {
    Surface(
        shape = AgentShapes.pill,
        color = LocalContentColor.current.copy(alpha = 0.10f),
        contentColor = LocalContentColor.current,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = if (onRemove == null) 14.dp else 4.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (attachment.kind) {
                    MediaKind.AUDIO -> Icons.Rounded.GraphicEq
                    MediaKind.VIDEO -> Icons.Rounded.Movie
                    else -> Icons.Rounded.Description
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = describeSize(attachment)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            if (onRemove != null) {
                FilledTonalIconButton(
                    onClick = { onRemove(attachment) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .minimumInteractiveComponentSize()
                        .size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Remove ${attachment.name}",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The type and size under a chip's name.
 *
 * The size is omitted rather than shown as "0 KB" when the provider would not
 * report one — an unknown size is a missing fact, not a zero one.
 */
private fun describeSize(attachment: OutgoingAttachment): String? {
    val type = attachment.mimeType.substringAfter('/').substringBefore(';').uppercase()
    val kb = attachment.sizeBytes / 1024
    return when {
        attachment.sizeBytes <= 0 -> type.ifBlank { null }
        kb < 1024 -> "$type · $kb KB"
        else -> "$type · ${kb / 1024} MB"
    }
}

/**
 * Decodes a picker URI small enough to draw.
 *
 * Two passes on purpose: the first reads only the dimensions, so the second can
 * be told to sample. A 4000px camera photo decoded whole is tens of megabytes,
 * and a strip of three of them is an OutOfMemoryError rather than a transcript.
 * A provider that cannot serve two reads (some cloud documents) surfaces as a
 * null here and a "could not be read" tile, which is a state the user can act on
 * — the send path reads the bytes itself and does not depend on this.
 */
private fun decodeThumbnail(context: Context, uri: String): ImageBitmap? {
    val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
    }.getOrNull()
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageSampleSize.forBounds(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX_DIMENSION)
    }
    return runCatching {
        context.contentResolver.openInputStream(parsed)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }.getOrNull()
}

/**
 * The pending strip's own frame: a bubble in the user's own shape.
 *
 * It is drawn between the transcript and the composer — the exact place the
 * turn's bubble will appear — so attaching a file reads as composing the next
 * message rather than as a dialog in front of it.
 */
@Composable
fun PendingAttachmentsBubble(
    attachments: List<OutgoingAttachment>,
    onRemove: (OutgoingAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = AgentShapes.bubbleTailEnd,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // Hugs the strip rather than filling the row: one thumbnail in a
            // full-width bubble reads as an empty message waiting to be filled.
            // The cap is what keeps a row of six photos from becoming the whole
            // screen — past it the strip scrolls sideways.
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            OutgoingAttachmentStrip(
                attachments = attachments,
                onRemove = onRemove,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * The references a *stored* user turn came with.
 *
 * A reopened conversation cannot show the thumbnail: the bytes are on the server
 * and the row has no picker URI any more. Re-fetching them purely to decorate
 * scrollback would mean an authenticated image request per row on every open, so
 * the row names the file instead and leaves the pixels to the agent's own view of
 * it. Naming it is also the honest answer to "what did I send?" — the server
 * renames an upload on the way in, and the name it chose is the one the agent
 * saw.
 */
@Composable
fun StoredRefStrip(
    refs: List<StoredRef>,
    modifier: Modifier = Modifier,
) {
    if (refs.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        refs.forEach { ref -> StoredRefChip(ref) }
    }
}

@Composable
private fun StoredRefChip(ref: StoredRef) {
    Surface(
        shape = AgentShapes.pill,
        // Tinted from the bubble's own content colour, so the chip reads as an
        // inset of the message rather than a second surface on top of it.
        color = LocalContentColor.current.copy(alpha = 0.10f),
        contentColor = LocalContentColor.current,
        modifier = Modifier.widthIn(max = 260.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (ref.kind == MediaKind.IMAGE) {
                    Icons.Rounded.Image
                } else {
                    Icons.Rounded.Description
                },
                contentDescription = if (ref.kind == MediaKind.IMAGE) "Attached image" else "Attached file",
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = ref.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
