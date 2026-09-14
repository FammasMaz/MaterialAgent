package com.materialagent.ui

import com.materialagent.ui.components.PullReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull-to-reveal arithmetic, tested off-device.
 *
 * Whether the panel *looks* right needs an emulator, but what the gesture does
 * with a finger's travel is ordinary arithmetic and is exactly the part that
 * fails invisibly: a resistance above 1 makes the panel lunge, a progress that
 * is not measured against the threshold makes the give happen somewhere the
 * thumb does not expect it, and an unclamped fraction draws a panel taller than
 * the screen. All of those are asserted here rather than trusted.
 */
class PullRevealTest {

    private val threshold = 200f

    @Test
    fun stretchKeepsHalfOfTheFingerTravel() {
        assertEquals(0f, PullReveal.stretch(0f, 0f), 0.001f)
        assertEquals(50f, PullReveal.stretch(0f, 100f), 0.001f)
        assertEquals(80f, PullReveal.stretch(60f, 40f), 0.001f)
    }

    @Test
    fun stretchIgnoresUpwardTravel() {
        assertEquals(120f, PullReveal.stretch(120f, -80f), 0.001f)
    }

    @Test
    fun stretchHasNoCeilingButIsNeverNegative() {
        // The reference clamps the shape it draws and not the distance it keeps,
        // so an enthusiastic pull is allowed to run past the threshold.
        val far = PullReveal.stretch(0f, 10_000f)
        assertEquals(5_000f, far, 0.001f)
        assertEquals(0f, PullReveal.stretch(0f, -10_000f), 0.001f)
    }

    @Test
    fun retractSpendsAnUpwardDragOnThePullFirst() {
        assertEquals(-40f, PullReveal.retract(100f, -40f), 0.001f)
    }

    @Test
    fun retractStopsAtZeroAndLeavesTheRestToTheList() {
        // Only the 20px of pull is spent; the other 30px must reach the list.
        assertEquals(-20f, PullReveal.retract(20f, -50f), 0.001f)
    }

    @Test
    fun retractDoesNothingWithoutAPullToSpend() {
        assertEquals(0f, PullReveal.retract(0f, -50f), 0.001f)
        assertEquals(0f, PullReveal.retract(50f, 30f), 0.001f)
    }

    @Test
    fun progressIsMeasuredInThresholds() {
        assertEquals(0f, PullReveal.progress(0f, threshold), 0f)
        assertEquals(0.5f, PullReveal.progress(threshold / 2f, threshold), 0.001f)
        assertEquals(1f, PullReveal.progress(threshold, threshold), 0f)
        assertEquals(2f, PullReveal.progress(threshold * 2f, threshold), 0f)
    }

    @Test
    fun progressOfAZeroThresholdIsRestRatherThanInfinity() {
        assertEquals(0f, PullReveal.progress(120f, 0f), 0f)
        assertEquals(0f, PullReveal.progress(120f, -5f), 0f)
    }

    @Test
    fun fractionClampsBothEnds() {
        assertEquals(0f, PullReveal.fraction(-2f), 0f)
        assertEquals(0f, PullReveal.fraction(0f), 0f)
        assertEquals(0.5f, PullReveal.fraction(0.5f), 0f)
        assertEquals(1f, PullReveal.fraction(1f), 0f)
        assertEquals(1f, PullReveal.fraction(4f), 0f)
    }

    @Test
    fun fractionTracksProgressAcrossTheTravel() {
        var pull = 0f
        repeat(20) {
            pull = PullReveal.stretch(pull, 40f)
            val progress = PullReveal.progress(pull, threshold)
            assertEquals(progress.coerceIn(0f, 1f), PullReveal.fraction(progress), 0.0001f)
        }
    }

    @Test
    fun bucketTakesOneStepPerTenthOfTheWayToGiving() {
        assertEquals(0, PullReveal.bucket(0f))
        assertEquals(0, PullReveal.bucket(0.09f))
        assertEquals(1, PullReveal.bucket(0.1f))
        assertEquals(9, PullReveal.bucket(0.99f))
        assertEquals(PullReveal.RATCHET_STEPS, PullReveal.bucket(1f))
        assertEquals(15, PullReveal.bucket(1.5f))
    }

    @Test
    fun giveThresholdIsInclusive() {
        assertFalse(PullReveal.hasGiven(0.999f))
        assertTrue(PullReveal.hasGiven(1f))
        assertTrue(PullReveal.hasGiven(3f))
    }

    @Test
    fun aThresholdOfPullCostsTwiceThatInFingerTravel() {
        var pull = 0f
        // 2 x the threshold in finger travel is what the 0.5 gain costs.
        repeat(10) { pull = PullReveal.stretch(pull, threshold / 5f) }
        assertEquals(threshold, pull, 0.001f)
        assertTrue(PullReveal.hasGiven(PullReveal.progress(pull, threshold)))
    }
}
