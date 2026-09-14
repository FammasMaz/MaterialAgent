package com.materialagent.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.MediaRef

/*
 * The file rows: a generic attachment (PDF, archive, anything the agent wrote)
 * and the video placeholder.
 *
 * They share one shape because they are the same object to the person reading
 * the transcript — a named thing with a type, a size if it is known, and an
 * action that saves it — and only the leading glyph differs. Video in particular
 * deliberately gets no player of its own: this app has no video pipeline, and a
 * hand-off to whatever viewer the user already has is both better and cheaper
 * than a second-rate one built here.
 */

/**
 * A non-image attachment: name, type, size once known, and a save action that
 * hands the file to another app.
 */
@Composable
fun FileAttachment(
    ref: MediaRef,
    url: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val env = LocalMediaEnvironment.current ?: return
    val download = rememberAttachmentDownload(env, url, ref)

    AttachmentRow(
        title = ref.name,
        subtitle = attachmentSubtitle(ref, download.sizeBytes),
        detail = caption,
        leading = { AttachmentGlyph(iconForKind(ref.kind)) },
        download = download,
        actionDescription = "Save and open this file",
        modifier = modifier,
    )
}

/**
 * A video: a still-less placeholder with a play glyph and a hand-off action.
 *
 * No thumbnail is fetched because the gateway offers no frame extraction, and
 * guessing one by downloading the whole file would make a transcript of videos
 * unusable on a phone connection.
 */
@Composable
fun VideoAttachment(
    ref: MediaRef,
    url: String,
    caption: String?,
    modifier: Modifier = Modifier,
) {
    val env = LocalMediaEnvironment.current ?: return
    val download = rememberAttachmentDownload(env, url, ref)

    // The caption is the agent's own words about the clip, so it takes the title
    // and the file name steps down to the detail line — the reverse of the file
    // row, where the name is the only thing the user can act on.
    val label = caption?.takeIf { it.isNotBlank() }
    AttachmentRow(
        title = label ?: ref.name,
        subtitle = attachmentSubtitle(ref, download.sizeBytes),
        detail = label?.let { ref.name },
        leading = {
            AttachmentGlyph(Icons.Rounded.PlayArrow, prominent = true)
        },
        download = download,
        actionDescription = "Open this video in another app",
        modifier = modifier,
    )
}

/**
 * The one-row layout the file and video attachments share.
 *
 * Kept private: it is a layout, not an attachment kind, and the moment it is
 * public it invites a caller to invent a fifth kind of attachment out of it.
 */
@Composable
private fun AttachmentRow(
    title: String,
    subtitle: String,
    detail: String?,
    leading: @Composable () -> Unit,
    download: AttachmentDownload,
    actionDescription: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                detail?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                download.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AttachmentDownloadButton(
                download = download,
                contentDescription = actionDescription,
            )
        }
    }
}

/** A square tile holding the attachment's glyph, so every row starts the same way. */
@Composable
private fun AttachmentGlyph(icon: ImageVector, prominent: Boolean = false) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (prominent) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (prominent) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

/** `PDF · 1.2 MB`, omitting whichever half is not known yet. */
private fun attachmentSubtitle(ref: MediaRef, sizeBytes: Long): String = listOfNotNull(
    ref.extension.uppercase().takeIf { it.isNotBlank() } ?: kindWord(ref.kind),
    sizeBytes.takeIf { it > 0L }?.let(::formatMediaSize),
).joinToString(" · ")

/**
 * The type as a word, for a file whose extension says nothing (or is missing —
 * the marker may name a directory, in which case there is no extension at all).
 */
private fun kindWord(kind: MediaKind): String = when (kind) {
    MediaKind.IMAGE -> "Image"
    MediaKind.AUDIO -> "Audio"
    MediaKind.VIDEO -> "Video"
    MediaKind.FILE -> "File"
}

private fun iconForKind(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.IMAGE -> Icons.Rounded.Image
    MediaKind.AUDIO -> Icons.Rounded.Audiotrack
    MediaKind.VIDEO -> Icons.Rounded.Movie
    MediaKind.FILE -> Icons.Rounded.Description
}
