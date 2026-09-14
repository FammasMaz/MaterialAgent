package com.materialagent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.materialagent.data.HapticCue
import com.materialagent.ui.theme.ExpressiveMotion

/** A top-level destination in the floating navigation bar. */
data class AgentDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * The app's primary navigation: a Material 3 Expressive floating toolbar with the
 * "new conversation" action living inside it as a vibrant FAB.
 *
 * The selected destination is the only one that shows its label — the toolbar
 * grows the active pill rather than five static tabs, so the shape of the bar
 * itself communicates where you are.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentNavBar(
    destinations: List<AgentDestination>,
    currentRoute: String?,
    onNavigate: (AgentDestination) -> Unit,
    onNewConversation: () -> Unit,
    onCue: (HapticCue) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        floatingActionButton = {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            FloatingToolbarDefaults.VibrantFloatingActionButton(
                onClick = {
                    onCue(HapticCue.TURN_START)
                    onNewConversation()
                },
                interactionSource = interaction,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New conversation")
            }
        },
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                destinations.forEach { destination ->
                    NavigationPill(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = {
                            onCue(HapticCue.SENT)
                            onNavigate(destination)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun NavigationPill(
    destination: AgentDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // A label that does not fit is worse than no label: at a large font scale
    // (or on a narrow screen) the expanded pill runs off the edge, so the icon
    // carries the destination on its own and `contentDescription` keeps it
    // announced. Same rule the Material 3 Expressive reference uses.
    val configuration = LocalConfiguration.current
    val showLabel = configuration.fontScale <= 1.25f && configuration.screenWidthDp >= 400

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = ExpressiveMotion.Specs.color,
        label = "pillContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = ExpressiveMotion.Specs.color,
        label = "pillContent",
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .pressScale(interaction)
            .animateContentSize(animationSpec = ExpressiveMotion.Specs.contentSize),
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                modifier = Modifier.size(if (pressed && !selected) 21.dp else 22.dp),
            )
            AnimatedVisibility(visible = selected && showLabel) {
                Row {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
