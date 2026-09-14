package com.materialagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialagent.data.HapticCue
import com.materialagent.ui.theme.ExpressiveMotion
import com.materialagent.ui.theme.cornerRadiusSpec
import com.materialagent.ui.theme.placementSpec

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

private val LABEL_GAP = 8.dp

/**
 * How wide this destination's label is, measured once.
 *
 * Measuring up front is what lets the reveal be a single animated `Dp` instead
 * of an animated *measured size*: the animation then has a known destination and
 * never has to ask the text how big it is mid-flight.
 */
@Composable
private fun rememberLabelWidth(label: String, showLabel: Boolean): Dp {
    if (!showLabel) return 0.dp
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(label, style, density, measurer) {
        val widthPx = measurer.measure(AnnotatedString(label), style = style).size.width
        with(density) { widthPx.toDp() }
    }
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
        modifier = Modifier.pressScale(interaction),
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The press feedback used to swap 22dp for 21dp in a single frame
            // while the colour and size around it sprang — a 1dp instant change
            // reads as a glitch. Same distance, but it travels.
            val iconSize by animateDpAsState(
                targetValue = if (pressed && !selected) 21.dp else 22.dp,
                animationSpec = cornerRadiusSpec(),
                label = "navIconSize",
            )
            Icon(
                imageVector = destination.icon,
                // Announced once: when the pill shows the label, the Text below
                // already carries the name, so the icon must not repeat it.
                contentDescription = if (selected && showLabel) null else destination.label,
                modifier = Modifier.size(iconSize),
            )
            // The label is revealed by animating exactly one scalar — its width
            // — instead of letting `animateContentSize` chase the pill's measured
            // size. The old version had two size animations fighting over the same
            // subtree (the pill's own, and an AnimatedVisibility nested inside it),
            // with that subtree re-measured every frame and the whole Row re-laid
            // out on top of it. Neighbouring pills visibly snapped sideways.
            // One width, measured once from the text, keeps each frame to a single
            // layout pass.
            val labelWidth = rememberLabelWidth(destination.label, showLabel)
            val reveal by animateDpAsState(
                targetValue = if (selected && showLabel) labelWidth + LABEL_GAP else 0.dp,
                animationSpec = placementSpec(),
                label = "navLabelReveal",
            )
            val labelAlpha by animateFloatAsState(
                targetValue = if (selected && showLabel) 1f else 0f,
                animationSpec = ExpressiveMotion.Specs.alpha,
                label = "navLabelAlpha",
            )

            Box(modifier = Modifier.width(reveal).clipToBounds()) {
                Row(
                    modifier = Modifier.alpha(labelAlpha),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(LABEL_GAP))
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelLarge,
                        // Pinned to its measured width and denied wrapping, so a
                        // half-revealed label is a clipped label rather than a
                        // reflowed one.
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(labelWidth),
                    )
                }
            }
        }
    }
}
