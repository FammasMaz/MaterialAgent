package com.materialagent.data

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [AttemptGate], the rule that a superseded connection attempt may not
 * publish.
 *
 * The case worth pinning is the one seen on a device: two attempts dial at once,
 * the earlier one wins and cancels the later one, and the cancelled attempt —
 * unwinding a moment afterwards — publishes its own verdict last. Here that is
 * the first claim publishing after the second one has been made.
 */
class AttemptGateTest {

    @Test
    fun theNewestClaimMayPublish() {
        val gate = AttemptGate()

        val only = gate.claim()

        assertTrue("the only attempt is the newest", gate.allows(only))
    }

    @Test
    fun aSupersededAttemptMayNoLongerPublish() {
        val gate = AttemptGate()
        val superseded = gate.claim()

        val replacement = gate.claim()

        assertFalse("the attempt that was replaced must be silenced", gate.allows(superseded))
        assertTrue("the attempt that replaced it speaks for the connection", gate.allows(replacement))
    }

    @Test
    fun claimsIncreaseSoASupersededAttemptStaysSuperseded() {
        val gate = AttemptGate()

        val first = gate.claim()
        val second = gate.claim()
        val third = gate.claim()

        assertTrue("claims must move forward", first < second && second < third)
        assertFalse(gate.allows(first))
        assertFalse(gate.allows(second))
        assertTrue(gate.allows(third))
    }

    @Test
    fun concurrentClaimsAreDistinctAndLeaveExactlyOnePublisher() {
        val gate = AttemptGate()
        val claimed = Collections.synchronizedList(mutableListOf<Int>())
        val start = CountDownLatch(1)

        val dials = List(THREADS) {
            thread {
                start.await()
                claimed.add(gate.claim())
            }
        }
        start.countDown()
        dials.forEach { it.join() }

        assertEquals("every claim must be distinct", THREADS, claimed.toSet().size)
        assertEquals("only the newest claim may publish", 1, claimed.count { gate.allows(it) })
    }

    private companion object {
        const val THREADS = 16
    }
}
