package com.materialagent.ui.components

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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
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
    val transition = rememberInfiniteTransition(label = "markSheen")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
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

                if (sheen) {
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
 * Cycles through Material shapes on a slow, non-overshooting tick. Used as the
 * "working" indicator, the empty-state hero and the session avatar, so the
 * agent has one recognisable silhouette everywhere.
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

    if (!active || reduced) {
        Canvas(modifier = modifier.size(size)) {
            drawPolygonPath(Morph(shapes.first(), shapes.first()).toPath(progress = 0f, startAngle = 0), gradient(colors))
        }
        return
    }

    val totalCycles = shapes.size
    val transition = rememberInfiniteTransition(label = "orb")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = totalCycles.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1100 * totalCycles, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "orbTick",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbAngle",
    )

    val morphs = remember(shapes) {
        shapes.indices.map { index ->
            Morph(shapes[index], shapes[(index + 1) % shapes.size])
        }
    }

    Canvas(modifier = modifier.size(size)) {
        val index = tick.toInt().coerceIn(0, totalCycles - 1)
        val progress = (tick - index).coerceIn(0f, 0.999f)
        val path = morphs[index].toPath(progress = progress, startAngle = 0)
        rotate(spin, pivot = center) {
            drawPolygonPath(path, gradient(colors))
        }
    }
}

/** Fits [path]'s bounding box to the draw area and fills it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolygonPath(
    path: Path,
    brush: Brush,
) {
    val bounds = path.getBounds()
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val fitted = Path().apply { addPath(path) }
    val matrix = Matrix().apply {
        translate(-bounds.left, -bounds.top)
        scale(size.width / bounds.width, size.height / bounds.height)
    }
    fitted.transform(matrix)
    drawPath(path = fitted, brush = brush)
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
    val transition = rememberInfiniteTransition(label = "livePulse")
    val scale by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Canvas(modifier = modifier.size(size * 1.6f)) {
        drawCircle(color = color.copy(alpha = alpha * 0.35f), radius = this.size.minDimension / 2f)
        drawCircle(color = color.copy(alpha = alpha), radius = this.size.minDimension / 2f * scale * 0.55f)
    }
}
