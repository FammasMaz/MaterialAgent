package com.materialagent.ui

import com.materialagent.data.HapticCue
import com.materialagent.ui.components.LiquidRipple
import com.materialagent.ui.components.PullRatchet
import com.materialagent.ui.components.PullRipple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reveal's ripple, tested off-device.
 *
 * What the wave *looks* like needs a screen, but two things about it do not, and
 * both are the kind that go wrong quietly. The first is the schedule: a ripple
 * that fires more than once for one pull, or that fires while the panel is
 * animating shut, is worse than no ripple at all — so the mapping from the pull's
 * cue to a wave is pinned here. The second is the wave's numbers, which are a port
 * from `sameerasw/essentials`; a well-meaning tweak to any of them is a different
 * effect, and the test says so out loud.
 */
class PullRippleTest {

    @Test
    fun onlyTheGiveRipples() {
        assertTrue(PullRipple.firesOn(HapticCue.REVEAL))
        assertFalse("a ratchet tick is a step, not the give", PullRipple.firesOn(HapticCue.REVEAL_TICK))
        assertFalse(PullRipple.firesOn(HapticCue.UI_ACTION))
        assertFalse(PullRipple.firesOn(HapticCue.SCROLL_TICK))
        assertFalse("a pull that has earned nothing ripples nothing", PullRipple.firesOn(null))
    }

    @Test
    fun aPullRipplesOnceWhenItGives() {
        val ratchet = PullRatchet()
        var waves = 0
        var progress = 0f
        while (progress < 1.2f) {
            progress += 0.01f
            if (PullRipple.firesOn(ratchet.onProgress(progress))) waves++
        }
        assertEquals("one give, one wave", 1, waves)
    }

    @Test
    fun aPullHeldPastTheThresholdDoesNotKeepRippling() {
        val ratchet = PullRatchet()
        assertTrue(PullRipple.firesOn(ratchet.onProgress(1f)))
        repeat(10) {
            assertFalse("a wave per frame is a seizure, not a ripple", PullRipple.firesOn(ratchet.onProgress(2.5f)))
        }
    }

    @Test
    fun closingTheRevealDoesNotRipple() {
        val ratchet = PullRatchet()
        assertTrue(PullRipple.firesOn(ratchet.onProgress(1f)))
        // The collapse animates the pull back to rest; every step of that is the
        // panel going away, not a new give.
        assertFalse(PullRipple.firesOn(ratchet.onProgress(0.75f)))
        assertFalse(PullRipple.firesOn(ratchet.onProgress(0.25f)))
        assertFalse(PullRipple.firesOn(ratchet.onProgress(0f)))
    }

    @Test
    fun pullingAgainAfterRestRipplesAgain() {
        val ratchet = PullRatchet()
        assertTrue(PullRipple.firesOn(ratchet.onProgress(1f)))
        ratchet.onProgress(0.3f)
        ratchet.onProgress(0f)
        assertTrue(PullRipple.firesOn(ratchet.onProgress(1f)))
    }

    @Test
    fun aMotionlessPullNeverRipples() {
        // Programmatic scrolling cannot reach the pull, but the mapping is flat
        // for a still surface as well — a streaming answer must not ripple.
        val ratchet = PullRatchet()
        repeat(20) { assertFalse(PullRipple.firesOn(ratchet.onProgress(0f))) }
    }

    @Test
    fun theWaveIsTheReferencesWave() {
        // sameerasw/essentials, ui/modifiers/LiquidRippleModifier.kt defaults as
        // passed by ui/composables/SetupFeatures.kt:1011.
        assertEquals(2800, LiquidRipple.DURATION_MILLIS)
        assertEquals(34f, LiquidRipple.AMPLITUDE_DP, 0f)
        assertEquals(12f, LiquidRipple.FREQUENCY, 0f)
        assertEquals(4.5f, LiquidRipple.DECAY, 0f)
        assertEquals(1400f, LiquidRipple.SPEED_DP, 0f)
    }
}
