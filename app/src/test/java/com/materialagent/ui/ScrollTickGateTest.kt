package com.materialagent.ui

import com.materialagent.ui.components.ScrollTickGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll-tick rule, tested off-device.
 *
 * Haptics themselves need a motor, but *when* they fire is ordinary logic and is
 * exactly the part that goes wrong quietly: a gate with no cap turns a fling
 * into a buzz, and one that swallows travel goes silent just when the user is
 * scrolling fastest.
 */
class ScrollTickGateTest {

    @Test
    fun smallMovementsAccumulateInsteadOfTickingEachTime() {
        val gate = ScrollTickGate(stepPx = 100f)
        assertFalse(gate.onScroll(40f, 0L))
        assertFalse(gate.onScroll(40f, 1_000_000L))
        assertTrue("40+40+40 crosses 100", gate.onScroll(40f, 2_000_000L))
    }

    @Test
    fun oneStepOfTravelTicksOnce() {
        val gate = ScrollTickGate(stepPx = 100f)
        assertTrue(gate.onScroll(100f, 0L))
        assertFalse("the travel was spent", gate.onScroll(1f, ScrollTickGate.DEFAULT_MIN_GAP_NANOS))
    }

    @Test
    fun aFlingCrossingManyStepsStillTicksOncePerGap() {
        val gate = ScrollTickGate(stepPx = 100f)
        assertTrue(gate.onScroll(2_000f, 0L))
        // Twenty steps' worth of travel inside one frame must not become twenty pulses.
        assertFalse(gate.onScroll(2_000f, 1_000_000L))
        assertFalse(gate.onScroll(2_000f, 2_000_000L))
        assertTrue(
            "past the cap a tick lands, and the held travel is not lost",
            gate.onScroll(1f, ScrollTickGate.DEFAULT_MIN_GAP_NANOS),
        )
    }

    @Test
    fun scrollingBackwardsTicksToo() {
        val gate = ScrollTickGate(stepPx = 100f)
        assertFalse(gate.onScroll(-60f, 0L))
        assertTrue(gate.onScroll(-60f, 1_000_000L))
    }

    @Test
    fun ticksResumeAfterTheGap() {
        val gate = ScrollTickGate(stepPx = 100f)
        assertTrue(gate.onScroll(100f, 0L))
        assertTrue(gate.onScroll(100f, ScrollTickGate.DEFAULT_MIN_GAP_NANOS))
        assertTrue(gate.onScroll(100f, ScrollTickGate.DEFAULT_MIN_GAP_NANOS * 2))
    }

    @Test
    fun aCustomGapIsHonoured() {
        val gate = ScrollTickGate(stepPx = 10f, minGapNanos = 500L)
        assertTrue(gate.onScroll(10f, 0L))
        assertFalse(gate.onScroll(10f, 499L))
        assertTrue(gate.onScroll(10f, 500L))
    }
}
