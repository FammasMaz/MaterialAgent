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

    /** Plays the pattern for a semantic cue at the user's chosen intensity. */
    fun perform(cue: HapticCue, level: HapticLevel) {
        if (level == HapticLevel.OFF || !supported) return
        val s = level.strength()
        when (cue) {
            // A single clean tick: "heard you".
            HapticCue.SENT, HapticCue.TOOL_DONE, HapticCue.INTERRUPTED -> pattern(s, listOf(0L))

            // Very light: the agent is alive and producing tokens.
            HapticCue.STREAM_TICK -> pattern(s * 0.45f, listOf(0L))

            HapticCue.TURN_START -> pattern(s * 0.7f, listOf(0L))

            // A rising double-tap: work has begun on the user's behalf.
            HapticCue.TOOL_START -> pattern(s * 0.7f, listOf(0L, 70L))

            // A distinct triple pulse — the only pattern that means "come back".
            HapticCue.NEEDS_ATTENTION -> pattern(s, listOf(0L, 90L, 90L), amplitudes = listOf(1f, 1f, 1.3f))

            // A small settle: completion should feel like a full stop.
            HapticCue.TURN_COMPLETE -> pattern(s * 0.7f, listOf(0L, 60L), amplitudes = listOf(1f, 0.5f))

            // A heavy low thud, unmistakably not-good.
            HapticCue.TURN_FAILED -> pattern(s, listOf(0L, 80L), amplitudes = listOf(1f, 0.7f), low = true)
        }
    }

    /** Direct, non-semantic feedback for ordinary UI touches. */
    fun tick(intensity: Float = 1f) {
        pattern(intensity.coerceIn(0.2f, 1f), listOf(0L))
    }

    /**
     * Emits one pulse per entry in [delaysMs]; each delay is the gap *before*
     * that pulse. [amplitudes] scales individual pulses relative to [scale].
     */
    private fun pattern(
        scale: Float,
        delaysMs: List<Long>,
        amplitudes: List<Float> = emptyList(),
        low: Boolean = false,
    ) {
        val vibrator = this.vibrator ?: return
        if (!supported) return
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
            vibrator.vibrate(composition.compose())
            return
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
        vibrator.vibrate(VibrationEffect.createWaveform(timings, levels, -1))
    }

    private val PULSE_MS = 18L

    private fun HapticLevel.strength(): Float = when (this) {
        HapticLevel.OFF -> 0f
        HapticLevel.SUBTLE -> 0.55f
        HapticLevel.STANDARD -> 0.8f
        HapticLevel.STRONG -> 1f
    }
}
