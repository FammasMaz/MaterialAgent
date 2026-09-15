package com.materialagent.ui.components.media

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The save action every attachment row ends with.
 *
 * Shared by the audio, video and file rows because it is one interaction — the
 * button, the spinner that replaces it while the fetch runs, and nothing else
 * competing for the tap — and three copies of it would drift apart in exactly
 * the state that matters (a download in flight).
 *
 * The label says "and open" wherever it is used: the file is handed straight to
 * another app, because it lands in the cache directory the user has no way to
 * browse. Saving without handing it over would leave nothing to see.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AttachmentDownloadButton(
    download: AttachmentDownload,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { download.start() },
        shapes = IconButtonDefaults.shapes(),
        enabled = !download.busy,
        // 44dp is the visual; the 48dp minimum is reserved inside the modifier so
        // the button keeps its size while the target stops being under-sized.
        modifier = modifier.minimumInteractiveComponentSize().size(44.dp),
    ) {
        if (download.busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Icon(Icons.Rounded.Download, contentDescription = contentDescription)
        }
    }
}
