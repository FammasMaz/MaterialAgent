@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.materialagent.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/*
 * The motion system.
 *
 * Material 3 Expressive's central motion idea is the split between *spatial* and
 * *effects* specs:
 *
 *  * **Spatial** (position, size, rotation, corner radius, scale) may overshoot —
 *    that bounce is what a settling sheet or an expanding tool card feels like.
 *  * **Effects** (colour, alpha, elevation) must never overshoot — a colour that
 *    bounces reads as a rendering bug.
 *
 * The theme installs a [MotionScheme] — `expressive()`, or `standard()` when the
 * user asks for less motion — and everything below *reads that scheme* instead of
 * spelling its own spring constants. Two things fall out of that. The app's own
 * animations and Material's built-in components finally run one motion system
 * rather than two: before this, the scheme was installed and never read, so a
 * switch and the pill beside it moved on different curves. And reduced motion
 * needs no branching at all — `MotionScheme.standard()` is critically damped, so
 * reading the scheme *is* the reduced-motion path.
 *
 * Call the helpers from a composable. They are deliberately not a plain object:
 * the scheme only differs from `expressive()` inside the theme's composition.
 */

/** Press/release scale and selectable-icon size. Spatial, fast — the snappiest step. */
@Composable
fun scaleSpec(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()

/** `animateContentSize` (an [IntSize]) — a spatial change, so it may settle with a bounce. */
@Composable
fun contentSizeSpec(): FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Corner-radius morphs, on the shared `animateDpAsState` path. */
@Composable
fun cornerRadiusSpec(): FiniteAnimationSpec<Dp> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Position changes (`Modifier.animateItem`, offsets, reveals). */
@Composable
fun <T> placementSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

/**
 * Hero moments — the composer action morphing send→steer→stop. Spatial and slow,
 * so the bounce is the expressive one rather than a hand-picked damping ratio.
 */
@Composable
fun <T> playfulSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

/**
 * Alpha/opacity. Effects, so this must never overshoot; under reduced motion the
 * standard scheme is already non-bouncy, which is all an effect can ask for.
 */
@Composable
fun alphaSpec(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

/** Colour transitions (`animateColorAsState` on a container or content role). */
@Composable
fun colorSpec(): FiniteAnimationSpec<Color> = MaterialTheme.motionScheme.defaultEffectsSpec()

/**
 * Specs for the two screens another agent still owns, which reference
 * `ExpressiveMotion.Specs.*` and cannot be edited here.
 *
 * These are built from `MotionScheme.expressive()` and so match the theme exactly
 * in the default case, but — being non-composable — they cannot follow the
 * reduced-motion branch. `SessionsScreen` and `SettingsScreen` therefore still
 * animate with expressive specs under reduced motion; moving them onto
 * [alphaSpec]/[colorSpec] is the follow-up that closes that gap.
 */
object ExpressiveMotion {
    private val scheme = MotionScheme.expressive()

    object Specs {
        val alpha: FiniteAnimationSpec<Float> = scheme.fastEffectsSpec()
        val color: FiniteAnimationSpec<Color> = scheme.fastEffectsSpec()
    }

    /** Press scale used across the app. */
    object Values {
        const val PRESSED_SCALE = 0.96f
    }
}
