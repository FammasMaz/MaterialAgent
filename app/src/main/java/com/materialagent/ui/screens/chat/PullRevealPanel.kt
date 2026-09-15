package com.materialagent.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.SessionSummary
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.ui.components.BlurEdge
import com.materialagent.ui.components.IconTooltip
import com.materialagent.ui.components.PullRevealState
import com.materialagent.ui.components.liquidRipple
import com.materialagent.ui.components.progressiveBlur

/** How tall the card stands once the pull has brought it all the way down. */
private val REVEAL_HEIGHT = 300.dp

/**
 * How far the blur reaches in from each edge of the card's scroll window.
 *
 * A gradient can be as short as it likes and still land, but a blur needs room to
 * go from full radius to none, and at 28dp the transition read as a line rather
 * than a dissolve.
 */
private val REVEAL_EDGE_BLUR = 48.dp

/**
 * The conversation's own details, brought down out of the header by the pull.
 *
 * This is a card, and the pull is what brings it down: at rest it sits entirely
 * above the top edge of the transcript, and [PullRevealState.fraction] slides it
 * into view as one piece, so the bottom edge of the card leads and the rest follows
 * it out. It is deliberately not a surface that grows in place — that reads as a
 * window opening, while a card that travels reads as something being dragged into
 * the conversation, which is what the gesture is.
 *
 * The card has to be a real surface — shape, container colour, elevation — for two
 * reasons, and neither is decoration. The rows sit on top of a transcript, so
 * without a surface behind them the message text shows through and the two read as
 * one broken layer. And the ripple the give fires is a displacement of *this*
 * surface: with a transparent panel there is nothing solid to displace, so the wave
 * smears the text instead of moving the card.
 *
 * The card is measured rather than assumed ([onSizeChanged]) because its height
 * follows the rows inside it, and it is the measurement that says how far above the
 * top edge it has to start.
 *
 * Its scroll window is blurred at both edges, the way `sameerasw/essentials` blurs
 * the edges of its scrolling screens: the metadata is a window onto more rows than
 * fit, so the row arriving at the top of that window and the row being cut off at
 * the bottom dissolve rather than end in a line. The collapse control is outside
 * that window and stays sharp.
 *
 * The revealed surface is the sheet's own body ([SessionInfoBody]), so the two can
 * never disagree about what the conversation is. Only the chrome differs — here a
 * collapse control, because this one is a layer over the transcript rather than a
 * sheet with its own dismiss gesture.
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

    // Seeded with the height the card will have, not zero: a card measured on its
    // first layout would be composed at `translationY = 0` for one frame and then
    // jump up out of the header, which is a flicker in the first moment of a drag.
    val nominalHeight = with(LocalDensity.current) { REVEAL_HEIGHT.roundToPx() }
    var cardHeight by remember { mutableIntStateOf(nominalHeight) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardHeight = it.height }
            // Fully above the top edge at rest, fully down at the give. The parent
            // clips, so the part that has not come down yet is simply not there.
            .graphicsLayer { translationY = -cardHeight * (1f - fraction) }
            // Mounted here, on the card, and nowhere else: the wave is the card's
            // reaction to the give, not a filter over the screen.
            .liquidRipple(trigger = rippleTrigger, enabled = rippleEnabled),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
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
                IconTooltip("Hide conversation info") {
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
            }

            // The scroll window, and the only thing blurred: the collapse control
            // above it is chrome and has no business being smeared.
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
