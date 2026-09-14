package com.materialagent.ui.components.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.MediaKind
import com.materialagent.core.model.MediaRef
import com.materialagent.core.model.MediaUrls
import com.materialagent.ui.components.MetaPill

/**
 * Everything one assistant turn attached, in the order the agent named it.
 *
 * This is the transcript's only entry point into media: one call site, one
 * decision about which renderer a kind gets. The URL each renderer fetches from
 * is built here — the gateway splits its file endpoints by purpose (images are
 * inlined as data URLs, audio is a range-able byte stream, everything else is a
 * download) and a caller who picked the wrong one would get a 404 or a JSON blob
 * where bytes were expected.
 *
 * Renders only the count when there is no signed-in server: every one of these
 * components needs a base URL to fetch from, and inventing one would produce
 * four identical failures in the transcript. Saying how many files arrived is
 * still better than dropping them silently — the prose above has already told
 * the reader that something was sent. What it must *not* do is leave it at the
 * bare count: a chip reading "1 attachment" under a turn whose audio is waiting
 * on the server is the dead end this strip exists to remove, so the count is
 * explained rather than left to look like a control that does nothing.
 */
@Composable
fun MediaStrip(
    media: List<MediaRef>,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty()) return
    val baseUrl = LocalMediaEnvironment.current?.baseUrl
    if (baseUrl.isNullOrBlank()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetaPill(
                text = if (media.size == 1) "1 attachment" else "${media.size} attachments",
                icon = Icons.Rounded.AttachFile,
            )
            Text(
                text = "Not connected — these files are on the server.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        media.forEach { ref ->
            // Keyed on the path: two attachments with identical captions must not
            // be confused for each other by the animation or the image cache.
            key(ref.path) {
                when (ref.kind) {
                    MediaKind.IMAGE -> ImageAttachment(
                        ref = ref,
                        url = MediaUrls.forImage(baseUrl, ref),
                        caption = ref.caption,
                    )

                    MediaKind.AUDIO -> AudioAttachment(
                        ref = ref,
                        url = MediaUrls.forAudio(baseUrl, ref),
                        caption = ref.caption,
                    )

                    MediaKind.VIDEO -> VideoAttachment(
                        ref = ref,
                        url = MediaUrls.forDownload(baseUrl, ref),
                        caption = ref.caption,
                    )

                    MediaKind.FILE -> FileAttachment(
                        ref = ref,
                        url = MediaUrls.forDownload(baseUrl, ref),
                        caption = ref.caption,
                    )
                }
            }
        }
    }
}
