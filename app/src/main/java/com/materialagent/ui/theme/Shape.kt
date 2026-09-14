package com.materialagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive shape scale.
 *
 * Two deliberate departures from baseline M3: every radius is bumped one step
 * (16 → 22, 28 → 32) because expressive surfaces read as *soft* objects, and
 * the small end stays tight enough that chips and tool badges still look like
 * instruments rather than pills.
 *
 * So this scale *is* the app's shape vocabulary, one step up from M3E's. Cards,
 * section containers and inner surfaces all read a step of it; the two places
 * that need M3E's own extended tokens read them from [androidx.compose.material3.MaterialTheme]
 * directly (`shapes.largeIncreased` for banners, `shapes.extraLargeIncreased` for
 * the composer's top corners), because those pass straight through this scale.
 *
 * Note on the extended tokens: `ShapeDefaults.extraLarge` and friends are *not*
 * visible to the Kotlin compiler in material3 1.5.0-alpha15 — only `Shapes`'
 * properties are (verified with a clean-file probe; `ShapeDefaults.<token>` fails
 * to resolve while `MaterialTheme.shapes.<token>` compiles). Anything reached
 * through `ShapeDefaults` in this file would not build.
 */
val MaterialAgentShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * The radii that can't be expressed as a step of the scale above.
 *
 * Every value here exists for a reason that a scale step cannot capture, and each
 * is named so the number has exactly one home.
 */
object AgentShapes {
    /**
     * The human's message bubble: three round corners and a squared-off tail on
     * the side the bubble points away from. Only the right-pointing variant is
     * used — the user's turn is always right-aligned — so there is no mirror of it.
     */
    val bubbleTailEnd = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp)

    /**
     * Sub-token radii that genuinely have no scale step: markdown block quotes and
     * the streaming caret are 2dp rules, not rounded boxes. Named because they
     * repeat, so the two can never drift apart.
     */
    val rule = RoundedCornerShape(2.dp)

    /**
     * The pill: chips, the model search field, the navigation bar, status pills and
     * the composer's tinted controls. 50% of the shorter side, so a long label stays
     * a true stadium instead of collapsing into a circle.
     */
    val pill = RoundedCornerShape(50)

    /**
     * The tray behind a fused [com.materialagent.ui.components.ExpressiveToggleGroup].
     *
     * This one has to be a number rather than a scale step: M3E draws the shared
     * outer corners of a connected button group with a *full* (50%) corner, so the
     * tray has to be at least half the tray's height for the two arcs to sit
     * concentrically. The group is ~48dp tall with its 4dp padding, which puts the
     * floor at 24dp; 28dp clears it with margin, and changing it means re-checking
     * that seam.
     */
    val toggleTray: Dp = 28.dp

    /**
     * The composer's radius, animated between these two: tight while a turn is
     * live so the send/steer/stop button sits close to the text, roomier when idle.
     * Dp values rather than shapes because `animateDpAsState` needs a number, and
     * they are a pair — changing one alone makes the morph look accidental.
     *
     * [composerActive] is M3E's `largeIncreased` (20dp) token. [composerIdle] is
     * 26dp: between the scale's `large` (22dp) and `extraLarge` (32dp), and with no
     * token of its own — 28dp (`extraLarge`) reads as the composer swallowing the
     * transcript behind it, 22dp as a plain card. Kept as a named pair so the one
     * off-scale number in the composer is impossible to miss.
     */
    val composerActive = 20.dp
    val composerIdle = 26.dp
}
