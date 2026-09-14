package com.materialagent.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.materialagent.core.model.SessionSummary
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.ui.components.PullRevealState

/** How tall the panel stands once it is fully out. */
private val PANEL_HEIGHT = 300.dp

/**
 * The conversation's metadata, drawn out of the top of the transcript by the
 * pull gesture.
 *
 * The panel's height is the only thing the gesture moves, and it is read
 * straight off [PullRevealState.fraction] — one scalar, one animation. The card
 * inside it is always laid out at its full [PANEL_HEIGHT] and simply clipped to
 * whatever the pull has earned so far, which is what keeps the body from
 * re-measuring on every frame of the drag: text cannot re-wrap in a viewport
 * that is not the one it is measured against.
 *
 * The revealed surface is the sheet's own body ([SessionInfoBody]), so the two
 * can never disagree about what the conversation is. Only the chrome differs —
 * here a title row with the collapse control, because this one is a layer over
 * the transcript rather than a sheet that has its own dismiss gesture.
 *
 * Nothing of the panel is composed while the pull is at rest, so a conversation
 * that is never pulled pays nothing for it.
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT * fraction)
            .clipToBounds()
            .padding(bottom = 6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_HEIGHT),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Conversation info",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
}
