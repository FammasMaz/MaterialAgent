package com.materialagent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialagent.data.HapticCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The arithmetic of pulling a list past its top edge.
 *
 * A list that has reached its first item still receives the rest of a downward
 * drag, and by default that leftover distance is ignored — nothing moves, which
 * reads as a broken gesture. Here it becomes a *pull*: the panel above the
 * transcript is drawn out of the top edge, and the pull deliberately does not
 * track the finger one-for-one. Half the travel is kept ([RESISTANCE]), and the
 * distance is measured against [THRESHOLD_DP]: under it the pull is resisting,
 * over it the gesture has given and the reveal stays out.
 *
 * Those two numbers are not guesses. `sameerasw/essentials` is the reference the
 * owner named, and its main screen
 * (`app/src/main/java/com/sameerasw/essentials/ui/composables/SetupFeatures.kt`,
 * `PullToRefreshBox` at ~line 1004 and the ratchet at ~line 983) gets the same
 * feel out of Material 3's pull-to-refresh machinery: the standard 0.5 drag gain
 * and the standard 80dp positional threshold, with the distance divided by that
 * threshold to give the `distanceFraction` everything else is read from. The
 * gradient from resisted pull to committed reveal is that fraction, so it is
 * what this file exposes as [PullRevealState.progress].
 *
 * Pure on purpose: the gain, the threshold and the ratchet steps are the parts
 * that go wrong invisibly, so they are checked by unit tests rather than by feel.
 */
object PullReveal {

    /** Share of the finger's travel the pull keeps. Below 1, this is the resistance. */
    const val RESISTANCE = 0.5f

    /** Pull distance, in dp, that counts as a full reveal — Material 3's positional threshold. */
    const val THRESHOLD_DP = 80f

    /**
     * Ratchet steps across the travel up to the threshold.
     *
     * Ten is the reference's number (`(fraction * 10).toInt()`), and it is the
     * number that makes the resistance audible under the thumb: one tick per
     * tenth of the way to giving.
     */
    const val RATCHET_STEPS = 10

    /**
     * The pull after [availableY] more pixels of downward drag.
     *
     * Upward drags are ignored here — retracting is [retract]'s job, and letting
     * this handle both would make a fling that overshoots the top edge pass
     * through nonsense intermediate values.
     *
     * The result is deliberately unbounded: what the pull *means* is
     * [progress], and it is the shape drawn from that which is clamped, exactly
     * as the reference clamps its card expansion and not its distance.
     */
    fun stretch(current: Float, availableY: Float): Float {
        if (availableY <= 0f) return current
        return (current + availableY * RESISTANCE).coerceAtLeast(0f)
    }

    /**
     * How much of an *upward* drag the pull spends before the list may scroll.
     *
     * Returned negative, because it is the amount of scroll to consume. A drag
     * that is longer than the pull only takes the pull back to zero: the rest
     * belongs to the list.
     */
    fun retract(current: Float, availableY: Float): Float {
        if (availableY >= 0f || current <= 0f) return 0f
        return if (current + availableY > 0f) availableY else -current
    }

    /**
     * The pull measured in thresholds — the reference's `distanceFraction`.
     *
     * 0 is at rest, 1 is the moment the gesture gives, and anything above 1 is
     * somewhere further into a pull the reveal has already committed to.
     */
    fun progress(pull: Float, thresholdPx: Float): Float =
        if (thresholdPx <= 0f) 0f else pull / thresholdPx

    /** Which tenth of the way to the threshold the pull has reached. */
    fun bucket(progress: Float): Int = (progress * RATCHET_STEPS).toInt()

    /** Whether the pull has reached the point where it gives. */
    fun hasGiven(progress: Float): Boolean = progress >= 1f

    /** [progress] as the 0..1 the reveal is drawn from. */
    fun fraction(progress: Float): Float = progress.coerceIn(0f, 1f)
}

/**
 * The cues a pull earns on its way to giving.
 *
 * Split out of the composable so the schedule is testable without a motor, and
 * kept as a class because it is a schedule: the decision at each point depends
 * on how far the pull had already come. The rule is the reference's, one for
 * one — a tick at every tenth of the way to the threshold, then a single,
 * different cue the moment the pull reaches it, and silence afterwards however
 * much further the finger travels.
 *
 * The reset only happens at exactly zero. That matters: a pull that is released
 * short of the threshold animates back down, and re-arming the ratchet on the
 * way would buzz the phone while the panel was closing.
 */
internal class PullRatchet {

    private var lastBucket = 0

    /** The cue, if any, this position on the pull earns. */
    fun onProgress(progress: Float): HapticCue? {
        val bucket = PullReveal.bucket(progress)
        var cue: HapticCue? = null

        if (progress >= 1f && lastBucket < PullReveal.RATCHET_STEPS) {
            cue = HapticCue.REVEAL
            lastBucket = PullReveal.RATCHET_STEPS
        } else if (progress < 1f && bucket != lastBucket) {
            // Only a pull that is still deepening ticks. Travel back out of a
            // pull is the user changing their mind, not a step to feel.
            if (bucket > lastBucket) cue = HapticCue.REVEAL_TICK
            lastBucket = bucket
        }

        if (progress <= 0f) lastBucket = 0
        return cue
    }
}

/**
 * The live pull, and whether it is latched open.
 *
 * One [Animatable] holds the pull, and every animated part of the reveal — the
 * panel's height, its opacity, the latched state's full height — is read out of
 * that single scalar through [fraction]. That is deliberate: a previous fix had
 * to remove a nested `animateContentSize` because two size animations over the
 * same subtree re-measured it every frame and made its neighbours snap. One
 * scalar, one animation, one layout owner.
 */
@Stable
class PullRevealState internal constructor(
    private val scope: CoroutineScope,
    /** The give threshold in pixels, converted once from [PullReveal.THRESHOLD_DP]. */
    private val thresholdPx: Float,
) {

    private val pull = Animatable(0f)

    /*
     * Whether the finger currently on the glass has actually stretched the pull.
     *
     * It has to be tracked, because a lift is not a pull: Compose delivers the
     * release to the child first, so a tap on the panel's own collapse button
     * runs [collapse] before the release watcher sees the same finger come up.
     * Without this flag that lift looked like a fresh pull that had given, and
     * the panel re-latched itself the instant it was closed.
     */
    private var stretchedByThisGesture = false

    /** True once the gesture passed the give threshold and the reveal is held open. */
    var isLatched by mutableStateOf(false)
        private set

    /**
     * The pull in thresholds: 0 at rest, 1 at the give, above 1 past it.
     *
     * This is what the cues are read from — not the scroll deltas that move it.
     */
    val progress: Float
        get() = PullReveal.progress(pull.value, thresholdPx)

    /** 0..1 through the reveal. The only animated value the panel reads. */
    val fraction: Float
        get() = if (isLatched) 1f else PullReveal.fraction(progress)

    internal fun snapTo(value: Float) {
        val clamped = value.coerceAtLeast(0f)
        stretchedByThisGesture = true
        scope.launch { pull.snapTo(clamped) }
    }

    /**
     * The finger has lifted.
     *
     * A pull that never reached the give threshold springs shut — the gesture
     * leaves no trace, which is what makes a small accidental drag at the top
     * harmless. One that did reach it latches instead of springing back: the
     * reference's main screen pins its expansion at full once the pull has
     * given, and the panel here carries ids to copy and numbers to read, so it
     * has to stay put once it is out.
     *
     * The latch is a snap rather than a spring, and that is deliberate. The
     * reveal is already drawn at its full height once the threshold is passed,
     * so there is nothing visible left to animate — but a spatial spring
     * settling *onto* the threshold swings past it on the way in, and swinging
     * back up through it is indistinguishable from a second pull arriving at
     * the give. It would fire the give a second time, a beat after the first,
     * for a gesture the user made once.
     */
    fun release(spec: FiniteAnimationSpec<Float>) {
        if (!stretchedByThisGesture) return
        stretchedByThisGesture = false
        if (isLatched || pull.value <= 0f) return
        if (PullReveal.hasGiven(progress)) {
            isLatched = true
            scope.launch { pull.snapTo(thresholdPx) }
        } else {
            scope.launch { pull.animateTo(0f, spec) }
        }
    }

    /** Puts the reveal away. The only way to close it, short of leaving the screen. */
    fun collapse(spec: FiniteAnimationSpec<Float>) {
        if (!isLatched) return
        isLatched = false
        scope.launch { pull.animateTo(0f, spec) }
    }

    /**
     * The nested-scroll policy.
     *
     * Upward drags are spent on retracting the pull before the list sees them,
     * so the gesture is cancelable without scrolling. Downward drags at the top
     * edge are taken the same way, in pre-scroll — which is the whole point of
     * taking them there rather than waiting for the leftover. A leftover would
     * mean the transcript had already been offered the drag, and the transcript's
     * own scroll texture (`scrollHaptics`) listens to every delta it is offered:
     * it would tick, in parallel with the ratchet, for movement that never
     * happened. Consuming first keeps the gesture to one voice — nothing is
     * scrolling, so only the ratchet speaks.
     *
     * The post-scroll half stays as the fallback for the frame where the list is
     * still settling onto its first item: there the list genuinely moves by the
     * last few pixels and only the remainder belongs to the pull.
     *
     * Both halves ignore anything that is not [NestedScrollSource.UserInput]. A
     * finger is the only thing that may move the pull, which is what makes the
     * ratchet that hangs off [progress] impossible to trigger by streaming: the
     * auto-scroll that follows an answer arrives as a side effect, never as user
     * input, whatever the list happens to be doing.
     */
    internal fun connection(atTop: () -> Boolean): NestedScrollConnection =
        object : NestedScrollConnection {

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (isLatched || source != NestedScrollSource.UserInput) return Offset.Zero

                val retracted = PullReveal.retract(pull.value, available.y)
                if (retracted != 0f) {
                    snapTo(pull.value + retracted)
                    return Offset(0f, retracted)
                }

                if (available.y > 0f && atTop()) {
                    snapTo(PullReveal.stretch(pull.value, available.y))
                    return Offset(0f, available.y)
                }

                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isLatched || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y <= 0f || !atTop()) return Offset.Zero
                snapTo(PullReveal.stretch(pull.value, available.y))
                // The whole delta is spent on the reveal: the list did not move,
                // and reporting it consumed is what stops it from also being flung.
                return Offset(0f, available.y)
            }
        }
}

/**
 * Remembers a [PullRevealState] for the lifetime of the composition it lives in.
 *
 * [giveDp] is the reveal's threshold: pull this far (after the resistance) and
 * the gesture has given.
 */
@Composable
fun rememberPullRevealState(giveDp: Dp = PullReveal.THRESHOLD_DP.dp): PullRevealState {
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { giveDp.toPx() }
    return remember(scope, thresholdPx) { PullRevealState(scope, thresholdPx) }
}

/**
 * Turns the leftover drag at a list's top edge into a pull, and settles it when
 * the finger lifts.
 *
 * Attach this to the container that holds both the reveal panel and the
 * scrollable, not to the scrollable itself: the pointer watcher that notices the
 * release has to keep seeing the finger even once it has travelled up into the
 * revealed panel.
 *
 * [atTop] is asked on every unconsumed delta rather than being watched as state,
 * because a list that is flinging past its top edge also reports leftover drag,
 * and a pull that started from a fling would jump its threshold instantly.
 */
fun Modifier.pullToReveal(
    state: PullRevealState,
    atTop: () -> Boolean,
    settleSpec: FiniteAnimationSpec<Float>,
): Modifier = composed {
    val top = rememberUpdatedState(atTop)
    this
        .nestedScroll(
            remember(state) { state.connection { top.value() } },
        )
        .pointerInput(state, settleSpec) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Release) state.release(settleSpec)
                }
            }
        }
}
