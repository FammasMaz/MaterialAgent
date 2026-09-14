package com.materialagent.ui

import com.materialagent.data.HapticCue
import com.materialagent.ui.components.PullRatchet
import com.materialagent.ui.components.PullReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pull's haptic schedule, tested off-device.
 *
 * The motor needs a phone, but *when* it fires is a small state machine and is
 * exactly the part that goes wrong quietly: one that re-ticks on a held pull
 * turns the resistance into a buzz, and one that does not re-arm buzzes the
 * phone while the panel is animating shut. Both are checked here.
 */
class PullRatchetTest {

    private fun cue(ratchet: PullRatchet, progress: Float): HapticCue? =
        ratchet.onProgress(progress)

    @Test
    fun ticksOncePerTenthOfTheWayToGiving() {
        val ratchet = PullRatchet()
        assertNull("rest earns nothing", cue(ratchet, 0f))
        assertNull("inside the first tenth, nothing yet", cue(ratchet, 0.05f))
        assertSame(HapticCue.REVEAL_TICK, cue(ratchet, 0.1f))
        assertNull("the same tenth does not tick twice", cue(ratchet, 0.15f))
        assertSame(HapticCue.REVEAL_TICK, cue(ratchet, 0.2f))
    }

    @Test
    fun theThresholdEarnsTheGiveInsteadOfTheTenthTick() {
        val ratchet = PullRatchet()
        cue(ratchet, 0.1f)
        cue(ratchet, 0.9f)
        assertSame(HapticCue.REVEAL, cue(ratchet, 1f))
        assertNull("the give does not repeat while the pull stays out", cue(ratchet, 1.2f))
        assertNull(cue(ratchet, 3f))
    }

    @Test
    fun aFullPullTicksNineTimesAndThenGives() {
        val ratchet = PullRatchet()
        val cues = mutableListOf<HapticCue>()
        var progress = 0f
        while (progress < 1.2f) {
            progress += 0.01f
            ratchet.onProgress(progress)?.let { cues += it }
        }
        assertEquals(
            "the tenth step is the give, not a tick",
            PullReveal.RATCHET_STEPS - 1,
            cues.count { it == HapticCue.REVEAL_TICK },
        )
        assertEquals(1, cues.count { it == HapticCue.REVEAL })
        assertSame("the give is the last thing felt", HapticCue.REVEAL, cues.last())
    }

    @Test
    fun aHeldPullDoesNotKeepTicking() {
        val ratchet = PullRatchet()
        cue(ratchet, 0.35f)
        repeat(5) { assertNull(cue(ratchet, 0.35f)) }
    }

    @Test
    fun easingBackOutOfAPullIsSilent() {
        val ratchet = PullRatchet()
        cue(ratchet, 0.5f)
        assertNull(cue(ratchet, 0.45f))
        assertNull(cue(ratchet, 0.2f))
        // Re-arming is the point of that silence: pulling deeper again ticks.
        assertSame(HapticCue.REVEAL_TICK, cue(ratchet, 0.55f))
    }

    @Test
    fun givingBackThenPullingAgainGivesAgain() {
        val ratchet = PullRatchet()
        assertSame(HapticCue.REVEAL, cue(ratchet, 1f))
        assertNull("on the way back out there is nothing to feel", cue(ratchet, 0.4f))
        assertSame(HapticCue.REVEAL, cue(ratchet, 1f))
    }

    @Test
    fun closingTheRevealDoesNotBuzz() {
        val ratchet = PullRatchet()
        assertSame(HapticCue.REVEAL, cue(ratchet, 1f))
        // The collapse animates the pull back to rest; every step of that must
        // be silent or the phone buzzes as the panel slides away.
        assertNull(cue(ratchet, 0.9f))
        assertNull(cue(ratchet, 0.5f))
        assertNull(cue(ratchet, 0.1f))
        assertNull(cue(ratchet, 0f))
        // And the ratchet is genuinely re-armed, not merely quiet.
        assertTrue(cue(ratchet, 0.2f) == HapticCue.REVEAL_TICK)
    }

    @Test
    fun aPullThatNeverMovesEarnsNothing() {
        // Programmatic scrolling cannot reach the pull at all, but the schedule
        // is asserted to be flat for a motionless pull as well.
        val ratchet = PullRatchet()
        repeat(20) { assertNull(cue(ratchet, 0f)) }
    }
}
