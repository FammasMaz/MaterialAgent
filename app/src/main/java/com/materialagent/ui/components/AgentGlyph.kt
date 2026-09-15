@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.materialagent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.materialagent.data.MotionLevel
import com.materialagent.ui.theme.LocalMotionLevel
import kotlinx.coroutines.delay
import kotlin.math.max

/*
 * The small "agent is working" mark.
 *
 * This is deliberately *not* the illustration. The owner's artwork carries a face,
 * hair and a mouth, which reads as an agent at 72dp and up and as grey mush at the
 * sizes the chat area can give it — 28dp beside "Agent is working", 22dp on a
 * streaming answer, 14dp inside the header pill. For those the right answer is a
 * *shape*: Material 3 Expressive ships a library of `RoundedPolygon`s
 * (`MaterialShapes`) that the framework's own components morph between, and one of
 * the burst/clover family reads as an agent mark — an organic, rounded glyph that
 * is still legible when it is twenty two pixels across.
 *
 * It also fixes a second inconsistency: the pill's sparkle icon was a rounded
 * `AutoAwesome` vector while the row beside it drew a picture, so one screen said
 * "the agent is working" three different ways. All three now draw this.
 *
 * The morph runs on the theme's own motion scheme, so reduced motion (a critically
 * damped `MotionScheme.standard()`) settles without a bounce, and a user who asked
 * for less motion gets a static glyph rather than a slower animation — the motion
 * carries no information here, so there is nothing to preserve.
 */

/**
 * The shapes the mark cycles through: a soft burst, a four-leaf clover and a
 * rounded nine-sided cookie. All three are organic and rounded, all three stay
 * readable at 14dp, and they are different enough that the cycle is visible without
 * ever looking like a spinner.
 */
private val AGENT_GLYPH_SHAPES: List<RoundedPolygon> = listOf(
    MaterialShapes.SoftBurst,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Cookie9Sided,
).map { it.normalized() }

/** How long the mark holds each shape before morphing into the next. */
private const val AGENT_GLYPH_HOLD_MILLIS = 300L

/**
 * Inset so the glyph keeps the optical margin a Material icon has inside its box,
 * instead of running edge to edge and reading heavier than everything beside it.
 */
private const val AGENT_GLYPH_INSET = 0.88f

/** The largest font scale the glyph follows; past this the row is already reflowing. */
private const val AGENT_GLYPH_MAX_FONT_SCALE = 2f

/**
 * The agent, as a Material shape.
 *
 * [active] turns the morph on — the callers that pass it are the ones that only
 * exist while a turn is running. Decorative: the text beside every call site already
 * says what is happening, so the glyph carries no `contentDescription`.
 *
 * The glyph follows the system font scale, because it sits in rows whose text grows
 * with that setting; a fixed 22dp mark beside 200%-scale text reads as a stray dot.
 */
@Composable
fun AgentShapeGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    active: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val reduced = LocalMotionLevel.current == MotionLevel.REDUCED
    val scheme = MaterialTheme.motionScheme
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, AGENT_GLYPH_MAX_FONT_SCALE)

    // A morph per consecutive pair, plus one from the last shape back to the first:
    // that closing morph is what lets the sequence loop without the mark ever
    // jumping back to its starting silhouette.
    val sequence = remember {
        AGENT_GLYPH_SHAPES.indices.map { i ->
            Morph(
                AGENT_GLYPH_SHAPES[i],
                AGENT_GLYPH_SHAPES[(i + 1) % AGENT_GLYPH_SHAPES.size],
            )
        }
    }
    // The widest shape decides the scale, so no member of the sequence is ever
    // clipped by the box the caller gave it.
    val extent = remember {
        AGENT_GLYPH_SHAPES.maxOf { polygon ->
            val bounds = polygon.calculateBounds()
            max(bounds[2] - bounds[0], bounds[3] - bounds[1])
        }
    }
    val workPath = remember { Path() }

    val progress = remember { Animatable(0f) }
    var index by remember { mutableIntStateOf(0) }

    val animating = active && !reduced
    LaunchedEffect(animating, sequence) {
        if (!animating) {
            // Reduced motion (or a finished turn) draws the first shape, still.
            index = 0
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            val result = progress.animateTo(
                targetValue = 1f,
                animationSpec = scheme.defaultSpatialSpec(),
            )
            // A cancelled animation is a recomposition, not a completed morph; let
            // the effect die rather than advancing the sequence under it.
            if (result.endReason != AnimationEndReason.Finished) break
            index = (index + 1) % sequence.size
            progress.snapTo(0f)
            delay(AGENT_GLYPH_HOLD_MILLIS)
        }
    }

    Canvas(modifier = modifier.size(size * fontScale)) {
        val side = this.size.minDimension
        val scale = (side / extent) * AGENT_GLYPH_INSET
        // The spatial spring is allowed to overshoot, and a `Morph` drawn past 1
        // would extrapolate its control points into a shape that does not exist, so
        // the overshoot is clamped for drawing while the spring settles behind it.
        val morph = sequence[index]
        val path = morph.toPath(
            progress = progress.value.coerceIn(0f, 1f),
            path = workPath,
            startAngle = 0,
        )
        path.transform(Matrix().apply { scale(scale, scale) })
        path.translate(this.size.center - path.getBounds().center)
        drawPath(path = path, color = color)
    }
}
