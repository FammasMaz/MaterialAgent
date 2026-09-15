package com.materialagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.materialagent.data.update.AppUpdate
import com.materialagent.data.update.UpdateState
import com.materialagent.data.update.bannerVisible
import com.materialagent.ui.theme.placementSpec
import com.materialagent.ui.theme.playfulSpec

/**
 * The app's one update surface, shown above every screen.
 *
 * A release is news the user did not ask for at that moment, so the banner never
 * blocks: it slides in, and both "not now" (dismiss) and "not ever for this
 * version" (skip) are one tap away. It is hosted once at the top of the app
 * rather than inside a screen, so an update is equally visible from the chat, the
 * sessions list and Settings.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: (AppUpdate) -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Entering is spatial, so it may overshoot; leaving must not, or a dismiss
    // reads as a rendering glitch. Both read the theme's motion scheme, which is
    // already `standard()` under reduced motion — so there is no separate branch
    // to forget here.
    val enter = playfulSpec<IntOffset>()
    val exit = placementSpec<IntOffset>()

    AnimatedVisibility(
        visible = state.bannerVisible,
        enter = slideInVertically(animationSpec = enter, initialOffsetY = { -it }),
        exit = slideOutVertically(animationSpec = exit, targetOffsetY = { -it }),
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            // No drop shadow: M3E raises a surface with a container role, and a
            // 6dp shadow on a bar pinned to the top edge read as Material 2.
        ) {
            // The container colour runs under the status bar; only the content
            // is pushed clear of it.
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                when (state) {
                    is UpdateState.Available -> AvailableContent(
                        update = state.update,
                        onUpdate = { onUpdate(state.update) },
                        onSkipVersion = onSkipVersion,
                        onDismiss = onDismiss,
                    )

                    is UpdateState.Downloading -> DownloadingContent(
                        update = state.update,
                        progress = state.progress,
                        onCancelDownload = onCancelDownload,
                    )

                    is UpdateState.ReadyToInstall -> ReadyContent(
                        update = state.update,
                        onInstall = onInstall,
                        onDismiss = onDismiss,
                    )

                    else -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AvailableContent(
    update: AppUpdate,
    onUpdate: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.NewReleases, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (update.isPreRelease) {
                    "Beta ${update.versionName} is available"
                } else {
                    "Version ${update.versionName} is available"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (update.assetSize > 0L) {
                    "Download ${formatBytes(update.assetSize)} and install"
                } else {
                    "A newer build is ready to download"
                },
                style = MaterialTheme.typography.bodySmall,
                // The role itself, de-emphasised by `bodySmall`: an 80%-alpha
                // content role drops under 4.5:1 in dark and at large font scales.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSkipVersion, shapes = ButtonDefaults.shapes()) {
            Text("Skip this version")
        }
        // `shapes`, not `shape`: M3E's button morphs its outline while pressed, and
        // the single-shape overload pins a static outline instead.
        Button(onClick = onUpdate, shapes = ButtonDefaults.shapes()) { Text("Update") }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadingContent(
    update: AppUpdate,
    progress: Float,
    onCancelDownload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = if (update.isPreRelease) "Downloading beta" else "Downloading update", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (progress >= 0f) {
                    "Version ${update.versionName} · ${(progress * 100).toInt()}%"
                } else {
                    "Version ${update.versionName}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        TextButton(onClick = onCancelDownload, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
    }

    Spacer(Modifier.height(8.dp))
    UpdateProgressBar(progress = progress)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadyContent(
    update: AppUpdate,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Ready to install", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (update.isPreRelease) {
                    "Beta ${update.versionName} downloaded"
                } else {
                    "Version ${update.versionName} downloaded"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Close, contentDescription = "Later")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Later") }
        Button(onClick = onInstall, shapes = ButtonDefaults.shapes()) { Text("Install") }
    }
}

/**
 * The download bar, shared with Settings so both places show the same thing.
 *
 * A negative [progress] means the server sent no content length: the bar runs
 * indeterminately rather than claiming a percentage it cannot know.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    // The track is a *container* role, not a faded content role: a 20%-alpha
    // `onPrimaryContainer` tracked the text colour, which is not what a track is.
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceAtLeast(0f),
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "updateDownloadProgress",
    )
    val stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val trackStroke = WavyProgressIndicatorDefaults.linearTrackStroke

    if (progress >= 0f) {
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
        )
    } else {
        LinearWavyProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = trackStroke,
        )
    }
}

/** Human-readable APK size, so the banner says what the download costs. */
private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
