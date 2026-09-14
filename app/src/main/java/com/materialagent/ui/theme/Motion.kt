package com.materialagent.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.materialagent.data.MotionLevel

/**
 * The motion system.
 *
 * Material 3 Expressive's central motion idea is the split between *spatial*
 * and *effects* springs:
 *
 *  * **Spatial** (position, size, rotation, corner radius, scale) is allowed to
 *    overshoot — `dampingRatio < 1`. This is the bounce you feel when a sheet
 *    settles or a tool card expands.
 *  * **Effects** (colour, opacity, elevation, blur) must never overshoot —
 *    `dampingRatio = 1.0`. A colour that bounces reads as a rendering bug.
 *
 * Every animation in the app draws from this object, so the feel stays coherent
 * and [MotionLevel.REDUCED] can flatten it in one place.
 */
object ExpressiveMotion {

    /** Springs for anything that moves through space. Bouncy by design. */
    object Spatial {
        private const val BOUNCE = 0.62f

        /** Default for most transitions. */
        fun <T> default(): SpringSpec<T> = spring(dampingRatio = BOUNCE, stiffness = 380f)

        /** Corner-radius morphs; stiffer so shape changes land quickly. */
        fun <T> shape(): SpringSpec<T> = spring(dampingRatio = BOUNCE, stiffness = 520f)

        /** Press/release scale on buttons and cards. */
        fun <T> press(): SpringSpec<T> = spring(dampingRatio = BOUNCE, stiffness = 700f)

        /** Screen-level container transforms. */
        fun <T> container(): SpringSpec<T> = spring(dampingRatio = BOUNCE, stiffness = 340f)

        /** Hero moments — the FAB, the streaming avatar, the banner shapes. */
        fun <T> playful(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = 420f)

        /** Height/size changes on growing message bubbles. */
        fun <T> size(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 300f)
    }

    /** Springs for colour, alpha, elevation. Critically damped — never bounce. */
    object Effects {
        private const val NO_BOUNCE = Spring.DampingRatioNoBouncy

        fun <T> color(): SpringSpec<T> = spring(dampingRatio = NO_BOUNCE, stiffness = 320f)

        fun <T> alpha(): SpringSpec<T> = spring(dampingRatio = NO_BOUNCE, stiffness = 300f)

        fun <T> elevation(): SpringSpec<T> = spring(dampingRatio = NO_BOUNCE, stiffness = 380f)
    }

    /** Type-safe presets, so call sites don't have to spell a generic. */
    object Specs {
        val scale: SpringSpec<Float> = Spatial.press()
        val alpha: SpringSpec<Float> = Effects.alpha()
        val bubbleSize: SpringSpec<Float> = Spatial.size()

        /** For `animateContentSize`, which animates an [IntSize], not a Float. */
        val contentSize: SpringSpec<androidx.compose.ui.unit.IntSize> = Spatial.size()
        val playful: SpringSpec<Float> = Spatial.playful()
        val cornerRadius: SpringSpec<Dp> = Spatial.shape()
        val elevation: SpringSpec<Dp> = Effects.elevation()
        val color: SpringSpec<androidx.compose.ui.graphics.Color> = Effects.color()
    }

    /** Press scale and travel distances used across the app. */
    object Values {
        const val PRESSED_SCALE = 0.96f
        const val SELECTED_SCALE = 1.02f
        const val DEFAULT_SCALE = 1f
    }
}

/**
 * Flat, non-bouncy specs for users who asked for less motion. Deliberately
 * still *animated* — reduced motion is not "no feedback", it's "no surprise".
 */
object ReducedMotion {
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    fun <T> fade(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)
}

/** Picks the spec set for the user's motion preference. */
@Suppress("UNCHECKED_CAST")
fun <T> motionFor(level: MotionLevel, spatial: Boolean): SpringSpec<T> =
    if (level == MotionLevel.REDUCED) {
        ReducedMotion.settle()
    } else {
        if (spatial) ExpressiveMotion.Spatial.default() else ExpressiveMotion.Effects.alpha()
    }

/*
 * Preference-aware specs for call sites.
 *
 * `motionFor` alone was not enough: call sites hardcoded
 * `ExpressiveMotion.Specs.*`, so choosing "Reduced" in settings flattened
 * nothing except the orb, while every other animation kept overshooting. These
 * read [LocalMotionLevel] straight off the theme instead, so one setting governs
 * the whole app.
 *
 * Only *spatial* travel is routed here. Effect specs (colour, alpha) are already
 * critically damped by construction, so they cannot overshoot and do not need to
 * change with the preference.
 */

/** Spec for anything that moves through space, honouring the motion setting. */
@Composable
fun scaleSpec(): SpringSpec<Float> = motionFor(LocalMotionLevel.current, spatial = true)

@Composable
fun contentSizeSpec(): SpringSpec<IntSize> = motionFor(LocalMotionLevel.current, spatial = true)

@Composable
fun cornerRadiusSpec(): SpringSpec<Dp> = motionFor(LocalMotionLevel.current, spatial = true)

/** Position changes (`Modifier.animateItem`, offsets) — spatial, so preference-aware. */
@Composable
fun <T> placementSpec(): SpringSpec<T> = motionFor(LocalMotionLevel.current, spatial = true)
