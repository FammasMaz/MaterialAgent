package com.materialagent.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialagent.R
import com.materialagent.data.MotionLevel
import com.materialagent.ui.theme.ExpressiveMotion
import com.materialagent.ui.theme.LocalMotionLevel
import com.materialagent.ui.theme.scaleSpec

/*
 * The expressive primitives this app is built from.
 *
 * Two ideas drive everything here:
 *  1. Shape is *state*: controls morph their outline on press and while working,
 *     rather than only changing colour.
 *  2. Motion is either spatial (may overshoot) or effects (must not) — never a
 *     mixture, which is what makes M3E feel intentional instead of wobbly.
 *
 * The agent itself is no longer a primitive. It used to be two hand-built vectors
 * here — a winged mark and a morphing orb — and neither of them matched the
 * illustration the app is branded with, which is why the app drew one agent on the
 * launcher icon and a different one on every screen inside it. What is left is
 * [AgentArt], which composes the real artwork at whichever of its two forms the
 * requested size can actually carry.
 */

/** Below this the illustration's detail stops reading, so the mark goes flat. */
private val DETAIL_SIZE = 48.dp

/**
 * The agent's mark: the owner's illustration, at the size it can actually be read.
 *
 * The artwork is a whole illustration — a face, its hair, a mouth, and the shapes
 * streaming away from it — which reads at a glance when it is large and collapses
 * into grey mush when it is small. So one composable covers both ends of that: at
 * [DETAIL_SIZE] and above it draws the colour illustration, and below it draws a
 * single-colour silhouette of the same drawing, which keeps the agent recognisable
 * in a 22dp avatar where nothing finer would survive.
 *
 * [active] and [sheen] are the ambient cues the vector mark and the morphing orb
 * used to carry. They are kept because callers use them to say "the agent is
 * working" without printing a spinner or any text: an active mark breathes, a
 * sheened mark carries a travelling highlight. Both are dropped entirely under
 * reduced motion — they are moods, not feedback, so a user who asked for less
 * motion loses no information.
 */
@Composable
fun AgentArt(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    active: Boolean = false,
    sheen: Boolean = false,
    /** Scales the travelling highlight: 0 leaves the mark plain, 1 is the full sweep. */
    sheenAmount: Float = 1f,
) {
    val reduced = LocalMotionLevel.current == MotionLevel.REDUCED
    val detailed = size >= DETAIL_SIZE
    // A breath is ambient — it says "alive", not "you did something" — so it has no
    // `MotionScheme` duration of its own. 1800ms is one slow breath, and the easing
    // is symmetric so the turn at each end has no velocity corner. It breathes down
    // rather than up so the mark never grows past the size its caller reserved.
    val breath = if (active && !reduced) {
        val transition = rememberInfiniteTransition(label = "agentBreath")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.96f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "breath",
        )
        value
    } else {
        1f
    }
    // The travelling highlight reverses rather than restarting, because a highlight
    // that teleports from the right edge back to the left is a jump cut.
    val sweep = if (sheen && !reduced) {
        val transition = rememberInfiniteTransition(label = "agentSheen")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse),
            label = "sweep",
        )
        value
    } else {
        0f
    }
    // A highlight tinted for whichever surface it sits on. Derived from the scheme
    // rather than hardcoded white, because a white highlight is invisible on the
    // light surface — and light is the default.
    val sheenColor = MaterialTheme.colorScheme.inverseOnSurface
        .copy(alpha = 0.35f * sheenAmount.coerceIn(0f, 1f))
    val showSheen = sheen && !reduced && sheenAmount > 0f

    Box(modifier = modifier.size(size).scale(breath)) {
        Image(
            painter = painterResource(
                if (detailed) R.drawable.ic_agent_art else R.drawable.ic_agent_art_silhouette,
            ),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            // The silhouette ships as flat white because the launcher's themed-icon
            // layer needs white; in the app it has to be tinted, or it would be
            // invisible on a light surface and off-brand on both.
            colorFilter = if (detailed) null else ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        if (showSheen) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val unit = this.size.minDimension
                val centre = Offset(unit * (0.15f + sweep * 0.7f), unit * 0.3f)
                val radius = unit * 0.28f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(sheenColor, Color.Transparent),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        }
    }
}

/** Scale-on-press with a spatial spring - the app's default touch response. */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = ExpressiveMotion.Values.PRESSED_SCALE,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = scaleSpec(),
        label = "pressScale",
    )
    return this.scale(scale)
}

/** Small breathing dot used to mark "live" sessions in lists. */
@Composable
fun LivePulse(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 8.dp,
) {
    // Ambient, not feedback: a "live" dot has no state to express and no
    // `MotionScheme` duration of its own, so this is one of the few places a fixed
    // duration is right — M3's `extraLong4` (1000ms). The easing is symmetric, so
    // the reversal at the end of each leg has no velocity corner; the linear ramp
    // it replaces snapped visibly at both turns.
    val pulse = tween<Float>(durationMillis = 1000, easing = FastOutSlowInEasing)
    val reduced = LocalMotionLevel.current == MotionLevel.REDUCED
    val (scale, alpha) = if (reduced) {
        1f to 1f
    } else {
        val transition = rememberInfiniteTransition(label = "livePulse")
        val pulseScale by transition.animateFloat(
            initialValue = 0.75f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(pulse, RepeatMode.Reverse),
            label = "pulse",
        )
        val pulseAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(pulse, RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        pulseScale to pulseAlpha
    }
    Canvas(modifier = modifier.size(size * 1.6f)) {
        drawCircle(color = color.copy(alpha = alpha * 0.35f), radius = this.size.minDimension / 2f)
        drawCircle(color = color.copy(alpha = alpha), radius = this.size.minDimension / 2f * scale * 0.55f)
    }
}
