package com.materialagent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/** One animation frame's worth of waiting between reveals. */
private const val FRAME_MS = 18L

/** How long to wait once the local cursor has caught up with the socket. */
private const val IDLE_MS = 40L

/**
 * Renders [text] as it arrives, but reveals it a few characters at a time.
 *
 * A gateway delivers text in whatever bursts its model produces — sometimes a
 * whole paragraph in one delta — which reads as the transcript jumping rather
 * than being written. Draining a local cursor against the latest text smooths
 * that out without ever stalling behind the socket: the reveal loop always
 * chases the newest value, and [streaming] going false publishes the rest of
 * the text immediately, so the authoritative final message is never held back
 * by an animation.
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = Color.Unspecified,
) {
    val latest by rememberUpdatedState(text)
    // A row that mounts already complete (reopened history, or a message that
    // finished while we were elsewhere) starts fully revealed.
    var revealed by remember { mutableIntStateOf(if (streaming) 0 else text.length) }

    LaunchedEffect(streaming) {
        if (!streaming) {
            revealed = latest.length
            return@LaunchedEffect
        }
        while (true) {
            val full = latest
            if (revealed >= full.length) {
                delay(IDLE_MS)
                continue
            }
            val remaining = full.length - revealed
            val step = when {
                remaining > 280 -> 24
                remaining > 120 -> 16
                remaining > 48 -> 10
                else -> 5
            }
            // Don't cut a word in half when the rest of it is already here.
            var next = (revealed + step).coerceAtMost(full.length)
            if (next < full.length) {
                val space = full.indexOf(' ', next)
                if (space != -1 && space - next <= 8) next = space + 1
            }
            revealed = next
            delay(FRAME_MS)
        }
    }

    val shown = if (revealed >= text.length) text else text.take(revealed.coerceAtLeast(0))
    MarkdownText(
        text = shown,
        style = style,
        color = color,
        modifier = modifier,
    )
}
