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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.max
import kotlin.math.min
import com.materialagent.data.MotionLevel
import com.materialagent.ui.theme.ExpressiveMotion
import com.materialagent.ui.theme.LocalMotionLevel
import com.materialagent.ui.theme.scaleSpec

/*
 * The expressive primitives this app is built from.
 *
 * Three ideas drive everything here:
 *  1. Shape is *state*: controls morph their outline on press and while working,
 *     rather than only changing colour.
 *  2. Motion is either spatial (may overshoot) or effects (must not) — never a
 *     mixture, which is what makes M3E feel intentional instead of wobbly.
 *  3. The agent has a body: a polymorphic orb that shifts shape while it thinks,
 *     so "working" is legible at a glance without reading any text.
 */

/** The Hermes mark, drawn as vectors so it can carry a gradient and animate. */
@Composable
fun AgentMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    gradient: Boolean = true,
    /**
     * How far to blend toward the gold accent: 0 is flat indigo, 1 is the full
     * sweep. Callers animate this instead of relying on [size], which used to
     * decide the blend behind their back.
     */
    sweepAmount: Float = 1f,
    sheen: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    // The sweep is a parameter, not a size threshold. Deriving it from `size`
    // meant a mark animating across 72dp hard-switched from the indigo→gold
    // gradient to flat indigo mid-flight, which read as a colour pop; and it made
    // `gradient = true` a lie at any size below the threshold. Callers animate the
    // amount, so the blend interpolates and the parameter does what it says.
    val mix = if (gradient) sweepAmount.coerceIn(0f, 1f) else 0f
    // Indigo and gold are complements, so a blend passes through khaki — which is
    // why the sweep is a caller's decision rather than automatic.
    val blendTo = lerp(tint, accent, mix)
    val brush = Brush.linearGradient(listOf(tint, blendTo))
    val faded = Brush.linearGradient(
        listOf(tint.copy(alpha = 0.82f), blendTo.copy(alpha = 0.82f)),
    )
    val reduced = LocalMotionLevel.current == MotionLevel.REDUCED
    // The travelling highlight is ambient ("the agent is alive"), not feedback, so
    // reduced motion drops it entirely. It reverses rather than restarting, because
    // a highlight that teleports from the right edge back to the left is a jump cut.
    val showSheen = sheen && !reduced
    val sweep = if (showSheen) {
        val transition = rememberInfiniteTransition(label = "markSheen")
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
    // A highlight tinted for whichever surface it sits on.
    val sheenColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.35f)

    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 108f
        translate(left = 0f, top = 0f) {
            scale(scale = unit, pivot = Offset.Zero) {
                // Wings — four blades, the outer pair longer than the inner.
                drawPath(wingPath(53f, 42f, 24f, 31f, 27.5f, 41f, 47f, 47.5f), brush)
                drawPath(wingPath(55f, 42f, 84f, 31f, 80.5f, 41f, 61f, 47.5f), brush)
                drawPath(wingPath(53f, 50f, 30f, 43f, 33f, 52f, 48f, 56.5f), faded)
                drawPath(wingPath(55f, 50f, 78f, 43f, 75f, 52f, 60f, 56.5f), faded)

                // Staff and apex.
                drawLine(
                    brush = brush,
                    start = Offset(54f, 33f),
                    end = Offset(54f, 87f),
                    strokeWidth = 6f,
                    cap = StrokeCap.Round,
                )
                // The gold apex is a single deliberate accent, legible at any
                // size, so it does not follow the sweep.
                drawCircle(accent, radius = 5.5f, center = Offset(54f, 21f))

                // Twin serpents.
                drawPath(serpentPath(45f, 49f, 61f, 55f, 49f, 63f, 54f, 73f), brush, style = Stroke(5.5f, cap = StrokeCap.Round))
                drawPath(serpentPath(63f, 49f, 47f, 55f, 59f, 63f, 54f, 73f), brush, style = Stroke(5.5f, cap = StrokeCap.Round))

                if (showSheen) {
                    // A travelling highlight sells "the agent is alive" without
                    // adding a spinner next to the brand. Derived from the scheme
                    // rather than hardcoded white, because a white highlight is
                    // invisible on the light surface — and light is the default.
                    val x = 18f + sweep * 74f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(sheenColor, Color.Transparent),
                            center = Offset(x, 30f),
                            radius = 26f,
                        ),
                        radius = 26f,
                        center = Offset(x, 30f),
                    )
                }
            }
        }
    }
}

private fun wingPath(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    x4: Float,
    y4: Float,
): Path = Path().apply {
    moveTo(x1, y1)
    lineTo(x2, y2)
    lineTo(x3, y3)
    lineTo(x4, y4)
    close()
}

private fun serpentPath(
    x1: Float,
    y1: Float,
    cx: Float,
    cy: Float,
    cx2: Float,
    cy2: Float,
    x2: Float,
    y2: Float,
): Path = Path().apply {
    moveTo(x1, y1)
    cubicTo(x1, y1, cx, cy, 54f, 61f)
    cubicTo(54f, 61f, cx2, cy2, x2, y2)
}

/**
 * The agent's polymorphic body.
 *
 * Metamorphoses through five Material shapes - easing into each one, holding it for
 * a beat, then easing on - in one fixed frame of reference, so nothing about the
 * drawn size or centre depends on the shape of the moment. Used as the "working"
 * indicator, the empty-state hero and the session avatar, so the agent has one
 * recognisable silhouette everywhere.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentOrb(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    active: Boolean = true,
    // Gold repeats as a second stop so the muddy blend occupies only the outer
    // rim of the shape instead of its whole body.
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    ),
) {
    val reduced = LocalMotionLevel.current == MotionLevel.REDUCED
    val shapes = remember {
        listOf(
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Clover8Leaf,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Flower,
        )
    }

    // One frame of reference for every shape and every point of every morph between
    // them: the union of the five shapes' bounds. The previous version fitted each
    // frame's *own* bounds to the canvas instead, so as a morph moved, the drawn
    // shape was re-fitted every frame - and because that fit scaled x and y
    // independently, the aspect ratio changed too. A silhouette change therefore
    // looked like a wobbling, stretching blob.
    val fit = remember(shapes) { unionBounds(shapes) }
    val morphs = remember(shapes) {
        shapes.indices.map { index -> Morph(shapes[index], shapes[(index + 1) % shapes.size]) }
    }

    if (!active || reduced) {
        Canvas(modifier = modifier.size(size)) {
            drawOrbPath(morphs.first().toPath(progress = 0f, startAngle = 0), gradient(colors), fit)
        }
        return
    }

    val steps = shapes.size
    // Ambient, like the brand mark's sheen and the live dot: the orb is a mood, not
    // a response to input, so it has no `MotionScheme` duration of its own. 1400ms a
    // shape is a beat long enough to read each silhouette before the next arrives.
    val stepMillis = 1400
    val holdFraction = 0.36f
    val transition = rememberInfiniteTransition(label = "orb")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = steps.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = stepMillis * steps, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "orbTick",
    )

    Canvas(modifier = modifier.size(size)) {
        val index = tick.toInt().coerceIn(0, steps - 1)
        val step = (tick - index).coerceIn(0f, 1f)
        // Morph across the first 64% of the step, hold for the rest: the silhouette
        // lands exactly on the integer boundary where the next Morph pair takes
        // over, so the loop is continuous and each shape gets a beat to be read.
        val progress = FastOutSlowInEasing
            .transform((step / (1f - holdFraction)).coerceIn(0f, 1f))
        drawOrbPath(morphs[index].toPath(progress = progress, startAngle = 0), gradient(colors), fit)
    }
}

/**
 * Fills [path] inside one fixed frame of reference, [fit] being the union of every
 * shape's bounds as `[minX, minY, maxX, maxY]`.
 *
 * The scale is a single factor for both axes, so a shape can be neither stretched
 * nor resized by the morph it happens to be part of.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrbPath(
    path: Path,
    brush: Brush,
    fit: FloatArray,
) {
    val shapeWidth = fit[2] - fit[0]
    val shapeHeight = fit[3] - fit[1]
    if (shapeWidth <= 0f || shapeHeight <= 0f) return
    val scale = min(size.width / shapeWidth, size.height / shapeHeight)
    val fitted = Path().apply { addPath(path) }
    fitted.transform(
        Matrix().apply {
            translate(
                size.width / (2f * scale) - (fit[0] + fit[2]) / 2f,
                size.height / (2f * scale) - (fit[1] + fit[3]) / 2f,
            )
            scale(scale, scale)
        },
    )
    drawPath(path = fitted, brush = brush)
}

/** The union of [shapes]' bounds as `[minX, minY, maxX, maxY]`. */
private fun unionBounds(shapes: List<RoundedPolygon>): FloatArray {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    val bounds = FloatArray(4)
    shapes.forEach { shape ->
        shape.calculateBounds(bounds)
        // Read the result as extremes rather than assuming its field order.
        minX = min(minX, min(bounds[0], bounds[2]))
        maxX = max(maxX, max(bounds[0], bounds[2]))
        minY = min(minY, min(bounds[1], bounds[3]))
        maxY = max(maxY, max(bounds[1], bounds[3]))
    }
    return floatArrayOf(minX, minY, maxX, maxY)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.gradient(colors: List<Color>): Brush =
    Brush.linearGradient(
        colors = if (colors.size == 1) listOf(colors[0], colors[0]) else colors,
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )

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

/** Convenience wrapper that owns its interaction source. */
@Composable
fun PressableBox(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        content()
    }
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
