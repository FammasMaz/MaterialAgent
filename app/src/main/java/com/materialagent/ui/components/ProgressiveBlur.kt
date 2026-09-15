package com.materialagent.ui.components

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Which edge of an element the blur is heaviest at. */
enum class BlurEdge { TOP, BOTTOM }

/**
 * How far the blur reaches in from the edge by default.
 *
 * `sameerasw/essentials` measures its top band from the status bar and uses a flat
 * 150dp at the bottom, where a composer sits; this app's chrome is the other way
 * round — a fixed header above, a floating composer below — so the default suits a
 * bar rather than a strip, and callers that know better pass their own.
 */
val DEFAULT_BLUR_HEIGHT = 96.dp

/** Radius at the outermost row of the band. The reference's 40. */
val DEFAULT_BLUR_RADIUS = 40.dp

/** Enough to carry the fade without hiding what is passing under it. */
private const val OVERLAY_ALPHA = 0.65f

/**
 * Blurs this element's content progressively towards one edge, so scrolling content
 * dissolves as it passes under the chrome instead of being cut off by it.
 *
 * The behaviour is a port of `sameerasw/essentials`' `ui/modifiers/ProgressiveBlurModifier.kt`,
 * which the owner asked for by name: their scrolling screens blur the top edge under
 * the status bar and the bottom edge behind the composer, at a radius of 40 and a
 * band of roughly 150dp, with a translucent overlay of the surface colour on top.
 * The one deliberate difference is that overlay's colour — that app sits on
 * `surfaceContainer`, this one's transcript sits on `surface`, and an overlay in the
 * wrong role reads as a grey smudge rather than as the background.
 *
 * The implementation is not theirs. They use `Modifier.blur` with a radius *gradient*
 * (`BlurRadiusSpec.verticalGradient`), which arrived with Compose UI 1.10; this app
 * resolves 1.11.0-alpha06 through its Material 3 dependency and has no such API, so
 * the radius gradient is drawn here by an AGSL shader instead. That is the same
 * machinery the reveal's ripple already uses and carries the same platform floor:
 * `RenderEffect.createRuntimeShaderEffect` needs Android 13, and below it the overlay
 * still fades, which is what the reference does on those versions too.
 *
 * A uniform blur would not do. It would smear the whole transcript, and its edges
 * would land on the band as a visible step; what makes this read as content
 * dissolving is that the radius is a function of distance — full at the outer edge,
 * zero by the far end of the band — so the shader interpolates it per fragment
 * rather than blurring at one strength and fading the result.
 *
 * It is skipped in power save mode: an offscreen render pass per frame is precisely
 * the sort of work that mode exists to stop, and the reference skips it there too.
 */
fun Modifier.progressiveBlur(
    edge: BlurEdge,
    radius: Dp = DEFAULT_BLUR_RADIUS,
    height: Dp = DEFAULT_BLUR_HEIGHT,
    overlay: Boolean = true,
    enabled: Boolean = true,
): Modifier = composed {
    val context = LocalContext.current
    val density = LocalDensity.current
    val powerSave = remember(context) { context.isPowerSaveMode() }
    val overlayColor = MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA)

    val supportsShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val blurring = enabled && radius > 0.dp && supportsShader && !powerSave

    val blurModifier = if (blurring) {
        val radiusPx = with(density) { radius.toPx() }
        val bandPx = with(density) { height.toPx() }
        // Two shaders rather than one: the vertical and horizontal halves of the blur
        // are separate render passes, and a shared instance would have to be
        // reconfigured between them inside a single frame.
        val vertical = remember { RuntimeShader(PROGRESSIVE_BLUR_AGSL) }
        val horizontal = remember { RuntimeShader(PROGRESSIVE_BLUR_AGSL) }
        Modifier
            .graphicsLayer {
                if (applyBlurShader(vertical, edge, size.height, bandPx, radiusPx, horizontalPass = false)) {
                    renderEffect = RuntimeEffect(vertical)
                } else {
                    renderEffect = null
                }
            }
            .graphicsLayer {
                if (applyBlurShader(horizontal, edge, size.height, bandPx, radiusPx, horizontalPass = true)) {
                    renderEffect = RuntimeEffect(horizontal)
                } else {
                    renderEffect = null
                }
            }
    } else {
        Modifier
    }

    val overlayModifier = if (overlay) {
        Modifier.drawWithContent {
            drawContent()
            drawEdgeOverlay(edge, height, overlayColor)
        }
    } else {
        Modifier
    }

    then(blurModifier).then(overlayModifier)
}

/**
 * Points a pass at this element and reports whether it has anything to do.
 *
 * Separating the two axes keeps each pass to nine taps; a single two-dimensional
 * kernel of the same quality would need eighty-one.
 */
private fun applyBlurShader(
    shader: RuntimeShader,
    edge: BlurEdge,
    sizeHeight: Float,
    bandPx: Float,
    radiusPx: Float,
    horizontalPass: Boolean,
): Boolean {
    if (sizeHeight <= 0f || bandPx <= 0f || radiusPx <= 0f) return false
    shader.setFloatUniform("uHeight", sizeHeight)
    shader.setFloatUniform("uBand", bandPx)
    shader.setFloatUniform("uRadius", radiusPx)
    shader.setFloatUniform("uTop", if (edge == BlurEdge.TOP) 1f else 0f)
    shader.setFloatUniform("uAxis", if (horizontalPass) 1f else 0f)
    return true
}

private fun RuntimeEffect(shader: RuntimeShader) =
    RenderEffect.createRuntimeShaderEffect(shader, "inputShader").asComposeRenderEffect()

private fun DrawScope.drawEdgeOverlay(edge: BlurEdge, height: Dp, color: Color) {
    val bandPx = height.toPx()
    if (bandPx <= 0f) return
    val brush = when (edge) {
        BlurEdge.TOP -> Brush.verticalGradient(
            colors = listOf(color, Color.Transparent),
            startY = 0f,
            endY = bandPx,
        )

        BlurEdge.BOTTOM -> Brush.verticalGradient(
            colors = listOf(Color.Transparent, color),
            startY = size.height - bandPx,
            endY = size.height,
        )
    }
    drawRect(brush = brush)
}

private fun Context.isPowerSaveMode(): Boolean {
    val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isPowerSaveMode
}

/**
 * One axis of a gaussian whose radius depends on the fragment's distance from the
 * blurred edge.
 *
 * `t` is how far into the band the fragment is: 0 at the edge itself, 1 at the far
 * end. The radius falls off as `(1 - t)²` so the transition reaches zero with a flat
 * tangent instead of leaving a line where the blur stops, and past the band the
 * shader returns its input untouched — the whole element is not blurred, only the
 * edge of it.
 *
 * The nine taps are spaced `radius / 4` apart, which puts the outermost sample at
 * roughly one radius and the kernel at about two standard deviations; that is the
 * point past which more taps stop being visible. Weights are the gaussian at each
 * offset, normalised by their sum, so a fragment never darkens or lightens from the
 * averaging itself.
 */
private const val PROGRESSIVE_BLUR_AGSL = """
    uniform shader inputShader;
    uniform float uHeight;
    uniform float uBand;
    uniform float uRadius;
    uniform float uTop;
    uniform float uAxis;

    half4 main(float2 p) {
        float distanceFromEdge = uTop > 0.5 ? p.y : (uHeight - p.y);
        float t = clamp(distanceFromEdge / uBand, 0.0, 1.0);
        float radius = uRadius * (1.0 - t) * (1.0 - t);
        if (radius < 0.5) return inputShader.eval(p);

        float2 direction = uAxis > 0.5 ? float2(1.0, 0.0) : float2(0.0, 1.0);
        float sigma = radius * 0.5;
        half4 sum = half4(0.0);
        float weightSum = 0.0;
        for (int i = -4; i <= 4; i++) {
            float offset = float(i) * radius / 4.0;
            float weight = exp(-0.5 * offset * offset / (sigma * sigma));
            sum += inputShader.eval(p + direction * offset) * half(weight);
            weightSum += weight;
        }
        return sum / half(weightSum);
    }
"""
