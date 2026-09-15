package com.materialagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialagent.ui.theme.alphaSpec
import com.materialagent.ui.theme.AgentShapes
import com.materialagent.ui.theme.contentSizeSpec

/*
 * Small shared surfaces. Each of these exists because the same shape appears on
 * at least three screens, and the alternative — three slightly different error
 * banners — is how an app stops looking designed.
 */

/**
 * The app's one error affordance.
 *
 * Errors are always dismissible, never block the screen, and always say what
 * failed. A retry action is offered only when retrying can plausibly help.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = contentSizeSpec()),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("Retry") }
            }
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Dismiss") }
        }
    }
}

/** A softer informational banner, used for things that are not failures. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NoticeBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("OK") }
            }
        }
    }
}

/** Section heading with an optional trailing action, used on every list screen. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // No horizontal padding of its own: callers already inset their
            // content by 16dp, and the extra 4dp here made every section header
            // sit 4dp right of the screen title above it.
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A compact status pill: label plus optional leading icon. */
@Composable
fun MetaPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = AgentShapes.pill,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                // A pill is a fixed one-line token. Server-supplied model names run
                // long (`deepseek-v3.2-exp-thinking`), and without this the text
                // wrapped inside the pill and grew it to two or three lines high.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The shared empty state. Every list gets one, and it always offers the action
 * that would fill the list — an empty screen with no way forward is a dead end.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    artSize: androidx.compose.ui.unit.Dp = 72.dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentArt(size = artSize, active = true)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            // `shapes`, not `shape`: the single-shape overload pins a static
            // outline, which is what made the app's buttons Material 2 on touch.
            //
            // The L size (56dp) rather than the default 40dp: an empty state exists
            // to offer exactly one way forward, so this is the screen's primary
            // action and it has to read as larger than the secondary one under it —
            // and larger than the 48dp icon buttons it sits among. The connect
            // screen's own submit button already asked for this much; this is the
            // same action arriving from a list, so it now matches instead of being
            // a size smaller than its twin.
            Button(
                onClick = onAction,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(actionLabel) }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction, shapes = ButtonDefaults.shapes()) {
                Text(secondaryActionLabel)
            }
        }
    }
}

/** Centred progress with a caption, for blocking loads. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingBlock(
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LoadingIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A thin inline divider with breathing room, used between settings groups. */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    // Same trap as the settings divider: a `Spacer` that only sets a height draws
    // nothing, so this rendered as an invisible 1dp gap.
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Reveals content with a soft fade instead of a hard pop. */
@Composable
fun SoftVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = alphaSpec()),
        exit = fadeOut(animationSpec = alphaSpec()),
    ) {
        Box { content() }
    }
}
