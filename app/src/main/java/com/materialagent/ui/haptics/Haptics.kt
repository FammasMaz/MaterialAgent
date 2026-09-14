package com.materialagent.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.materialagent.data.HapticLevel
import com.materialagent.data.HapticCue
import kotlin.math.roundToInt

/**
 * The app's single haptic vocabulary.
 *
 * Every buzz in MaterialAgent is one of a handful of *meanings*, not a raw
 * pattern scattered at call sites. That matters for an agent client: the phone
 * has to tell you, from a pocket, the difference between "the agent is typing",
 * "the agent needs your approval", and "the turn finished".
 *
 * Two implementations behind one API: `VibrationEffect.Composition` primitives
 * on API 31+ (short, crisp, consistent across OEMs), and an equivalent
 * amplitude waveform on API 26–30 so the vocabulary never silently disappears.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val supported: Boolean = vibrator?.hasVibrator() == true

    /**
     * Effects are built once and reused rather than rebuilt per call.
     *
     * This is not a micro-optimisation. `STREAM_TICK` fires once per revealed
     * chunk while the transcript drains — roughly every 18 ms — and each of
     * those calls used to allocate a `Composition` builder and a
     * `VibrationEffect` on the main thread. That is exactly the kind of work
     * that shows up as a dropped frame halfway through a stream, which is the
     * worst possible moment for one. There are at most ten cues times four
     * levels, so the table is bounded and tiny.
     */
    private val cueEffects = HashMap<Pair<HapticCue, HapticLevel>, VibrationEffect>()
    private val tickEffects = HashMap<Int, VibrationEffect>()

    /** Plays the pattern for a semantic cue at the user's chosen intensity. */
    fun perform(cue: HapticCue, level: HapticLevel) {
        if (level == HapticLevel.OFF || !supported) return
        val effect = cueEffects.getOrPut(cue to level) { build(cue, level) }
        this.vibrator?.vibrate(effect)
    }

    private fun build(cue: HapticCue, level: HapticLevel): VibrationEffect {
        val s = level.strength()
        return when (cue) {
            // A single clean tick: "heard you".
            HapticCue.SENT, HapticCue.TOOL_DONE, HapticCue.INTERRUPTED -> effect(s, listOf(0L))

            // Very light: the agent is alive and producing tokens.
            HapticCue.STREAM_TICK -> effect(s * 0.45f, listOf(0L))

            // The lightest pulse the motor can produce, and the one that fires
            // most often: a list being scrolled. Anything heavier turns a flick
            // through a long transcript into a rattle.
            HapticCue.SCROLL_TICK -> effect(s * 0.22f, listOf(0L))

            // A deliberate tap on a control that opens something. Heavier than a
            // scroll tick (it is intentional) but lighter than a confirmation.
            HapticCue.UI_ACTION -> effect(s * 0.38f, listOf(0L))

            // Flipping a switch or a segment. Reads as a detent, not as "sent".
            HapticCue.TOGGLE -> effect(s * 0.45f, listOf(0L))

            // A refresh crossing its threshold: firm enough to confirm the
            // gesture, under the level reserved for attention.
            HapticCue.REFRESH -> effect(s * 0.55f, listOf(0L))

            // Irreversible and gone. The heaviest pulse in the vocabulary on
            // purpose — this is the one moment where a distinct thud earns its
            // place, and it is why delete no longer fires TURN_FAILED.
            HapticCue.DESTRUCTIVE -> effect(s * 0.9f, listOf(0L, 40L))

            HapticCue.TURN_START -> effect(s * 0.7f, listOf(0L))

            // A rising double-tap: work has begun on the user's behalf.
            HapticCue.TOOL_START -> effect(s * 0.7f, listOf(0L, 70L))

            // A distinct triple pulse — the only pattern that means "come back".
            HapticCue.NEEDS_ATTENTION -> effect(s, listOf(0L, 90L, 90L), amplitudes = listOf(1f, 1f, 1.3f))

            // A small settle: completion should feel like a full stop.
            HapticCue.TURN_COMPLETE -> effect(s * 0.7f, listOf(0L, 60L), amplitudes = listOf(1f, 0.5f))

            // A heavy low thud, unmistakably not-good.
            HapticCue.TURN_FAILED -> effect(s, listOf(0L, 80L), amplitudes = listOf(1f, 0.7f), low = true)
        }
    }

    /** Direct, non-semantic feedback for ordinary UI touches. */
    fun tick(intensity: Float = 1f) {
        if (!supported) return
        // Quantised so repeated taps at slightly different pressures share one
        // cached effect instead of building a new one each time.
        val bucket = ((intensity.coerceIn(0.2f, 1f) * 20f).roundToInt())
        val effect = tickEffects.getOrPut(bucket) { effect(bucket / 20f, listOf(0L)) }
        this.vibrator?.vibrate(effect)
    }

    /**
     * Emits one pulse per entry in [delaysMs]; each delay is the gap *before*
     * that pulse. [amplitudes] scales individual pulses relative to [scale].
     */
    private fun effect(
        scale: Float,
        delaysMs: List<Long>,
        amplitudes: List<Float> = emptyList(),
        low: Boolean = false,
    ): VibrationEffect {
        val amplitudeFor: (Int) -> Float = { index ->
            val relative = amplitudes.getOrNull(index) ?: 1f
            (scale.coerceIn(0.15f, 1f) * relative).coerceIn(0.15f, 1f)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val primitive = if (low) {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_TICK
            }
            val composition = VibrationEffect.startComposition()
            delaysMs.forEachIndexed { index, delay ->
                composition.addPrimitive(
                    primitive,
                    amplitudeFor(index),
                    delay.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            return composition.compose()
        }

        // Pre-S fallback: the same rhythm as a silence/pulse waveform. Each
        // entry in [delaysMs] is the gap before that pulse.
        val timings = LongArray(delaysMs.size * 2)
        val levels = IntArray(delaysMs.size * 2)
        delaysMs.forEachIndexed { index, delay ->
            timings[index * 2] = delay
            levels[index * 2] = 0
            timings[index * 2 + 1] = PULSE_MS
            levels[index * 2 + 1] = (amplitudeFor(index) * 255f).roundToInt()
        }
        return VibrationEffect.createWaveform(timings, levels, -1)
    }

    private val PULSE_MS = 18L

    private fun HapticLevel.strength(): Float = when (this) {
        HapticLevel.OFF -> 0f
        HapticLevel.SUBTLE -> 0.55f
        HapticLevel.STANDARD -> 0.8f
        HapticLevel.STRONG -> 1f
    }
}
