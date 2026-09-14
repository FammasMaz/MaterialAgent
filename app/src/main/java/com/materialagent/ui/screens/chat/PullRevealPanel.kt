package com.materialagent.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.SessionSummary
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.ui.components.PullRevealState

/** How tall the reveal stands once it is fully out. */
private val REVEAL_HEIGHT = 300.dp

/** How far the content still has to travel when the pull starts. */
private val REVEAL_SETTLE = 12.dp

/** Height of the soft edge the reveal dissolves into the transcript with. */
private val REVEAL_FADE = 28.dp

/**
 * The conversation's own details, drawn out of the top of the transcript by the
 * pull gesture.
 *
 * This is the transcript growing a header, not a dialog opening over it: there is
 * no card, no container and no title here — the surface is still the surface the
 * messages are on, and the rows say what they are. A box inside the reveal would
 * read as something that was always there and is now being shown, which is the
 * opposite of what the pull just did.
 *
 * The height is the only thing the gesture moves, and it is read straight off
 * [PullRevealState.fraction] — one scalar, one animation. Two more things are
 * derived from that same fraction: the content rides down by [REVEAL_SETTLE] and
 * travels up into place as the pull pays for it, so the header looks dragged into
 * existence rather than switched on. Deriving it means there is no second
 * animation to keep in step with the height, and nothing that can arrive out of
 * order while a finger is still moving. Both follow the finger on the way back
 * out as well, because both are functions of the pull rather than of its history.
 *
 * The rows themselves are always laid out at the full [REVEAL_HEIGHT] and simply
 * clipped to what the pull has earned, which is what keeps the body from
 * re-measuring on every frame of the drag: text cannot re-wrap in a viewport that
 * is not the one it was measured against. Once the pull is past the threshold the
 * whole thing is out and scrolls as one, so a long list of facts stays reachable
 * without the panel having to grow past its share of the screen.
 *
 * The revealed surface is the sheet's own body ([SessionInfoBody]), so the two
 * can never disagree about what the conversation is. Only the chrome differs —
 * here a collapse control, because this one is a layer over the transcript rather
 * than a sheet that has its own dismiss gesture.
 *
 * Nothing is composed while the pull is at rest, so a conversation that is never
 * pulled pays nothing for it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PullRevealPanel(
    state: PullRevealState,
    transcript: ChatTranscript,
    summary: SessionSummary?,
    onCue: (HapticCue) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = state.fraction
    if (fraction <= 0f) return

    val settle = with(LocalDensity.current) { REVEAL_SETTLE.toPx() * (1f - fraction) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(REVEAL_HEIGHT * fraction)
            .clipToBounds(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(REVEAL_HEIGHT)
                .graphicsLayer { translationY = settle },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The only control the reveal needs: the pull opened it, and this
                // is how it goes back. It stays put while the rows scroll under it.
                IconButton(
                    onClick = onCollapse,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Hide conversation info",
                    )
                }
            }

            SessionInfoBody(
                transcript = transcript,
                summary = summary,
                onCue = onCue,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )
        }

        // The bottom edge is the pull's own edge, and it lands wherever the finger
        // left it — mid-row, mid-word. Without this the cut reads as a rendering
        // fault rather than as content still coming out. With it, the last row
        // dissolves into the transcript instead, which is also the cue that there
        // are more rows to scroll to once the reveal is fully out.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(REVEAL_FADE)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                    ),
                ),
        )
    }
}
