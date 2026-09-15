package com.materialagent.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import com.materialagent.data.HapticCue

/**
 * The ripple the reveal gives off when the pull crosses its threshold.
 *
 * This is a port, not an invention. The owner named `sameerasw/essentials` as the
 * app whose pull-down animation they wanted and called it "very ripply", and its
 * ripple is not a Material shape drawn over the content: it is a liquid
 * *displacement* of the content itself, an AGSL shader run through a
 * `RenderEffect` (`app/src/main/java/com/sameerasw/essentials/ui/modifiers/
 * LiquidRippleModifier.kt`, applied from the main screen's `PullToRefreshBox` at
 * `ui/composables/SetupFeatures.kt:1011`). A circle expanding over a transcript
 * would be a different effect wearing the same name, so what is ported here is
 * the shader and its numbers.
 *
 * The shader is a radial wave. Every fragment is pushed *along* the line joining
 * it to the origin by a decaying sine, and delayed by its own distance from that
 * origin — so the displacement leaves the origin first and arrives further out
 * later, which is what reads as a ripple travelling through the content rather
 * than the whole screen wobbling at once. A second, weaker wave follows 220 ms
 * behind on a slightly higher frequency, so the surface does not settle with the
 * mechanical regularity of a single sine. A fraction of the raw displacement is
 * added back as brightness, which is the specular flash the reference has and a
 * plain content shift would not.
 *
 * Three things are deliberately *not* ported:
 *
 * 1. **Where it is mounted.** The reference wraps its whole main screen, because
 *    its pull expands a card in the middle of the list. Here the gesture pulls the
 *    transcript out from under a header, so the ripple belongs on the transcript
 *    alone ([ChatScreen] mounts it there): the panel arrives through the pull's
 *    own animation, and what the ripple shows is the messages *settling* under it.
 * 2. **Where it starts.** The reference passes the origin of its expanding card's
 *    text. Here the surface was pulled *down* from the top edge, so the wave starts
 *    at the top centre of the layer it is given — the displacement then travels
 *    downwards with the content, instead of radiating from a point nobody touched.
 * 3. **How it is gated.** The reference fires it whenever the pull passes the
 *    threshold. Here it is additionally silent under reduced motion, which the
 *    reference has no notion of.
 *
 * Everything is pure displacement of what is already on screen: no colour, shape
 * or size of any element changes, so a ripple over a settled transcript cannot
 * misreport anything.
 *
 * `RenderEffect.createRuntimeShaderEffect` needs API 33. Below that the modifier
 * is inert — exactly as it is in the reference, whose `liquidRipple` returns the
 * untouched `Modifier` on older platforms. The reveal itself (the resistance, the
 * give, the haptics, the panel) is unchanged there.
 */
object LiquidRipple {

    /**
     * How long the wave is allowed to run, in milliseconds.
     *
     * The reference's 2800. It looks generous for an effect whose visible part is
     * over in under a second, but the tail is not decoration: the envelope decays
     * exponentially (`exp(-decay * t)`), so a shorter window would cut the wave off
     * mid-swing instead of letting it die away, and the cut is visible as a jump.
     */
    const val DURATION_MILLIS = 2800

    /**
     * Peak displacement, in dp, of the fastest-moving point of the wave.
     *
     * The reference's 34. Large on purpose — this is a liquid surface, and at a
     * couple of dp the whole effect would be indistinguishable from the list
     * redrawing itself.
     */
    const val AMPLITUDE_DP = 34f

    /** Radians per second of the primary wave. The reference's 12. */
    const val FREQUENCY = 12f

    /**
     * Exponential decay per second. The reference's 4.5, which puts the wave
     * roughly nine tenths of the way to still by half a second.
     */
    const val DECAY = 4.5f

    /**
     * How fast the wave's front travels, in dp per second.
     *
     * The reference's 1400: a fragment's displacement is delayed by its distance
     * from the origin divided by this, so a phone-height of content is crossed in
     * well under a second and the wave never outlives the pull that caused it.
     */
    const val SPEED_DP = 1400f
}

/**
 * Which of the pull's cues sets the ripple going.
 *
 * The ripple hangs off the *cue* rather than off a second threshold check, because
 * the pull's schedule is already the answer to "when did the gesture give": one
 * [HapticCue.REVEAL] per give, silent while the pull is held out, re-armed only
 * when the pull returns to rest, and silent on the way back out as the panel
 * closes (`PullRatchet`). Deriving the ripple from it is what makes the ripple and
 * the give feel like one event, and it is why there is nothing here that can fire
 * from a streaming answer's auto-scroll — that never moves the pull at all.
 *
 * The cue is read *before* the haptics engine sees it, so a user who has turned
 * haptics off still gets the ripple: silence is a choice about the phone, not
 * about the screen.
 */
object PullRipple {

    /** Whether this cue, if any, is the one that ripples. */
    fun firesOn(cue: HapticCue?): Boolean = cue == HapticCue.REVEAL
}

/**
 * Runs a liquid ripple across this element's already-drawn content.
 *
 * [trigger] is a counter, not a flag: incrementing it starts a new wave, and 0
 * never starts one. The wave is a one-shot — it is not proportional to the pull
 * and does not track the finger, because the displacement it draws is a wave
 * travelling through settled content and there is nothing to track it to; what the
 * *pull* controls is the reveal itself, and this is the surface's reaction to the
 * moment the pull gave.
 *
 * Setting `renderEffect` is what makes this cheap enough to use on a scrolling
 * list: the content keeps drawing normally, and only the frames the wave is
 * actually running ask the GPU to run the shader over the layer. The effect is
 * cleared on the last frame of every wave, so a ripple that is interrupted (a
 * collapse, a navigation, reduced motion being switched on) cannot leave the
 * transcript permanently displaced.
 */
fun Modifier.liquidRipple(
    trigger: Int,
    enabled: Boolean = true,
    origin: Offset = Offset.Unspecified,
    durationMillis: Int = LiquidRipple.DURATION_MILLIS,
    amplitudeDp: Float = LiquidRipple.AMPLITUDE_DP,
    frequency: Float = LiquidRipple.FREQUENCY,
    decay: Float = LiquidRipple.DECAY,
    speedDp: Float = LiquidRipple.SPEED_DP,
): Modifier = composed {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this

    val density = LocalDensity.current
    val elapsed = remember { Animatable(0f) }
    val shader = remember { RuntimeShader(LIQUID_RIPPLE_AGSL) }

    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        elapsed.snapTo(0f)
        elapsed.animateTo(
            targetValue = durationMillis / 1000f,
            animationSpec = tween(durationMillis = durationMillis, easing = LinearEasing),
        )
        // Back to exactly 0, which is the branch that drops the render effect.
        elapsed.snapTo(0f)
    }

    this.graphicsLayer {
        val time = elapsed.value
        val lifetime = durationMillis / 1000f
        if (time <= 0f || time >= lifetime) {
            renderEffect = null
            return@graphicsLayer
        }

        val start = if (origin.isSpecified) origin else Offset(size.width / 2f, 0f)
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uOrigin", start.x, start.y)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uAmplitude", amplitudeDp * density.density)
        shader.setFloatUniform("uFrequency", frequency)
        shader.setFloatUniform("uDecay", decay)
        shader.setFloatUniform("uSpeed", speedDp * density.density)
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "inputShader")
            .asComposeRenderEffect()
    }
}

/**
 * The wave itself, verbatim from `sameerasw/essentials` (MIT) —
 * `ui/modifiers/LiquidRippleModifier.kt`, `LIQUID_RIPPLE_AGSL`.
 *
 * `delay` is the arrival time at this fragment, so every point on the surface runs
 * the same wave and only its start differs; the second wave is the reference's
 * `subTime - 0.22` follow-up at 55% amplitude, 1.15x the frequency and 0.8x the
 * decay; and the last line adds 16% of the wave's normalised height back as a
 * brightness lift, which is what makes the crest read as a highlight on a glassy
 * surface rather than as content simply sliding.
 */
private const val LIQUID_RIPPLE_AGSL = """
    uniform shader inputShader;
    uniform float2 uResolution;
    uniform float2 uOrigin;
    uniform float uTime;
    uniform float uAmplitude;
    uniform float uFrequency;
    uniform float uDecay;
    uniform float uSpeed;

    half4 main(float2 fragCoord) {
        float2 pos = fragCoord;
        float distance = length(pos - uOrigin);
        float delay = distance / uSpeed;
        float time = max(0.0, uTime - delay);

        float wave1 = uAmplitude * sin(uFrequency * time) * exp(-uDecay * time);

        float subTime = max(0.0, time - 0.22);
        float wave2 = (uAmplitude * 0.55) * sin(uFrequency * 1.15 * subTime) * exp(-(uDecay * 0.8) * subTime);

        float totalWave = wave1 + wave2;
        float2 n = normalize(pos - uOrigin);
        float2 newPos = pos + totalWave * n;

        float highlight = 0.16 * (totalWave / max(1.0, uAmplitude));

        return inputShader.eval(newPos) + half4(highlight, highlight, highlight, 0.0);
    }
"""
