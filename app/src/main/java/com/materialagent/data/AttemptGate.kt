package com.materialagent.data

import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides which connection attempt is allowed to publish a status.
 *
 * Attempts overlap in practice. The launch connect runs in the container's
 * scope, the app's own ON_START can ask for a redial while that connect is still
 * dialling, the settings screen can start a third, and a finished attempt starts
 * a watcher that keeps publishing for as long as the socket lives. The attempt
 * that *finishes* last is not the attempt that *started* last, and the loser's
 * verdict is worth nothing: it is usually a cancellation, which is not a fact
 * about the server at all.
 *
 * Left unguarded that ordering is not theoretical. The launch connect won while
 * an ON_START redial was also in flight; the winner published `Connected` and,
 * as its own bookkeeping, cancelled the redial it had just replaced — and the
 * cancelled redial, unwinding a moment later, published `Idle` on top. The app
 * was left with an open socket, a loaded transcript and a status that denied
 * both: the sessions header offered to connect a server that was already
 * connected, and every surface that reads the status for an address — media rows
 * included — lost the address it fetches from.
 *
 * So an attempt claims a number when it starts and may only publish while that
 * number is still the newest. Anything older is dropped, which is what makes a
 * cancelled attempt harmless instead of merely usually harmless.
 */
class AttemptGate {

    private val newest = AtomicInteger(0)

    /** Claims the next number, superseding every attempt that came before it. */
    fun claim(): Int = newest.incrementAndGet()

    /** Whether [attemptId] is still the newest, and so still allowed to publish. */
    fun allows(attemptId: Int): Boolean = newest.get() == attemptId
}
