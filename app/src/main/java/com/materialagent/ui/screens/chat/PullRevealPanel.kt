package com.materialagent.ui.screens.chat

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.SessionSummary
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.ui.components.BlurEdge
import com.materialagent.ui.components.PullRevealState
import com.materialagent.ui.components.liquidRipple
import com.materialagent.ui.components.progressiveBlur

/** How tall the reveal stands once it is fully out. */
private val REVEAL_HEIGHT = 300.dp

/**
 * How far the card has travelled down once the pull is fully out.
 *
 * The pull brings this card down out of the header, so it is the card that moves —
 * not just its height. A card that only gains height reads as a window opening;
 * a card that also travels down out of the top edge reads as something being
 * dragged into the conversation, which is what the gesture is.
 */
private val CARD_TRAVEL = 28.dp

/**
 * How far the blur reaches in from each edge of the scroll window.
 *
 * Wider than the fade it replaced: a gradient can be as short as it likes and still
 * land, but a blur has to have room to go from full radius to none, and at 28dp the
 * transition read as a line rather than a dissolve.
 */
private val REVEAL_EDGE_BLUR = 48.dp

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
 * Two things are read off [PullRevealState.fraction], from the one scalar the
 * gesture already produces: the card's height, and how far down it has travelled
 * ([CARD_TRAVEL]). Deriving both means there is no second animation to keep in
 * step, and nothing that can arrive out of order while a finger is still moving.
 * Both follow the finger on the way back out as well, because both are functions
 * of the pull rather than of its history.
 *
 * The card is also the surface the ripple runs across — the pull moves the card, so
 * the card is what ripples, and the transcript under it is never touched.
 *
 * Its scroll window is blurred at both edges, the way `sameerasw/essentials` blurs
 * the edges of its scrolling screens: the metadata is a window onto more rows than
 * fit, so the rows arriving at the top of that window and the row being cut off at
 * the bottom are blurred out rather than chopped. The collapse control is outside
 * that window and stays sharp.
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
    rippleTrigger: Int,
    rippleEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = state.fraction
    if (fraction <= 0f) return

    val travel = with(LocalDensity.current) { CARD_TRAVEL.toPx() * fraction }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(REVEAL_HEIGHT * fraction)
            .graphicsLayer { translationY = travel }
            .clipToBounds()
            // Mounted here, on the card, and nowhere else: the wave is the card's
            // reaction to the give, not a filter over the screen.
            .liquidRipple(trigger = rippleTrigger, enabled = rippleEnabled),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(REVEAL_HEIGHT),
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

            // The scroll window, and the only thing blurred: the collapse control
            // above it is chrome and has no business being smeared.
            //
            // The bottom edge is the pull's own edge, and it lands wherever the finger
            // left it — mid-row, mid-word. Blurring it is what keeps the cut from
            // reading as a rendering fault, and the overlay the modifier draws carries
            // the same dissolve the old gradient fade did.
            Box(modifier = Modifier.weight(1f)) {
                SessionInfoBody(
                    transcript = transcript,
                    summary = summary,
                    onCue = onCue,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .progressiveBlur(edge = BlurEdge.TOP, height = REVEAL_EDGE_BLUR)
                        .progressiveBlur(edge = BlurEdge.BOTTOM, height = REVEAL_EDGE_BLUR),
                )
            }
        }
    }
}
