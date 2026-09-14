package com.materialagent.ui.components

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialagent.data.HapticCue
import com.materialagent.ui.rememberCue
import kotlin.math.abs

/**
 * Decides when scrolling has earned a tick.
 *
 * Split out of the modifier so the rule can be tested without a device: a tick
 * lands once per [stepPx] of travel, and never twice within [minGapNanos].
 *
 * That second clause is what keeps this pleasant. A hard fling can cross twenty
 * steps in one frame; without a cap the motor would be handed twenty pulses at
 * once and the effect reads as a rattle rather than as texture. When the cap
 * holds a tick back the accumulated travel is kept, so the tick lands as soon as
 * the gap closes instead of the movement being thrown away.
 */
class ScrollTickGate(
    private val stepPx: Float,
    private val minGapNanos: Long = DEFAULT_MIN_GAP_NANOS,
) {
    private var carried = 0f
    private var lastTick = Long.MIN_VALUE / 2

    /** True when this much movement should produce a tick. */
    fun onScroll(deltaPx: Float, nowNanos: Long): Boolean {
        carried += abs(deltaPx)
        if (carried < stepPx) return false
        if (nowNanos - lastTick < minGapNanos) return false
        carried = 0f
        lastTick = nowNanos
        return true
    }

    companion object {
        /** Roughly three frames: enough to separate ticks, short enough to track a finger. */
        const val DEFAULT_MIN_GAP_NANOS = 45_000_000L
    }
}

/**
 * A tick of feedback for every [tickEvery] the user scrolls.
 *
 * Driven by nested scroll rather than by watching which item is on screen. That
 * choice matters: programmatic scrolling — the transcript following a streaming
 * answer, or a jump to the newest message — does not travel through the nested
 * scroll chain, so an answer arriving never buzzes the phone. Only a finger
 * does. Feedback has to mean "you moved something", or the user's only recourse
 * is to switch it off.
 */
fun Modifier.scrollHaptics(
    enabled: Boolean = true,
    tickEvery: Dp = 48.dp,
): Modifier = composed {
    val cue = rememberCue()
    val stepPx = with(LocalDensity.current) { tickEvery.toPx() }
    val connection = remember(cue, enabled, stepPx) {
        object : NestedScrollConnection {
            private val gate = ScrollTickGate(stepPx)

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pre-scroll sees every delta; post-scroll only sees what the list
                // did not consume, which is nothing once it is scrolling normally.
                if (enabled && gate.onScroll(available.y, System.nanoTime())) {
                    cue(HapticCue.SCROLL_TICK)
                }
                // Never consume: this only listens, the list still does the scrolling.
                return Offset.Zero
            }
        }
    }
    nestedScroll(connection)
}
