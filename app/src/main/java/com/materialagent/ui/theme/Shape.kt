package com.materialagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
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
     * A fused [com.materialagent.ui.components.ExpressiveToggleGroup]: the drawn
     * height of one item, the inset the tray keeps around it, and the tray's own
     * corner radius.
     *
     * These are one decision, so they are derived rather than each guessed. The end
     * items of a connected group carry a *full* outer corner (`ButtonGroupDefaults`
     * maps the group's outer edge to `CornerFull`, i.e. half the item's height), so
     * a tray that insets them by [toggleTrayInset] is only concentric — equal arc,
     * equal ring — at `itemHeight / 2 + inset`.
     *
     * [toggleItemHeight] is the *drawn* height, not merely a minimum. M3E's own
     * `ToggleButton` draws a 40dp container inside a 48dp touch target, and an app
     * that lays those untuned boxes out inside a tray gets 8dp of ring above and
     * below but only 4dp at the sides, with no single radius able to match both —
     * the ring then reads as thick at the corner and thin along the edge, which is a
     * mistake rather than padding. Pinning the item to 48dp removes the mismatch, and
     * 48dp is M3's minimum touch target anyway, so it costs nothing.
     */
    val toggleItemHeight: Dp = 48.dp
    val toggleTrayInset: Dp = 4.dp

    /**
     * The tray's radius at [toggleItemHeight]; the live value for the current font
     * scale comes from [trayGeometry]. Kept as the documented base case so the
     * derivation below has a name.
     */
    val toggleTray: Dp = toggleItemHeight / 2 + toggleTrayInset

    /**
     * The ring a card keeps around a tray it hosts.
     *
     * This is the number that makes the two concentric: a container whose corners
     * are *concentric* with the tray inside it is exactly the tray's radius plus
     * this ring, and it is the only ring the tray ever sits in (the Settings label
     * column pushes the tray further in on the leading edge, where an oversized
     * outer radius stops mattering). A card that hosts a tray therefore reads
     * [ToggleTrayGeometry.hostCardRadius], never a hand-picked radius.
     */
    val trayHostInset: Dp = 16.dp

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

/**
 * The radii a fused tray wants at the current font scale: the tray itself and a
 * card that hosts it. Derived together because they are one decision.
 *
 * The item height `ToggleButton` settles on is a *floor* — [AgentShapes.toggleItemHeight]
 * — plus whatever the label's line box needs, and the label grows with the font
 * scale. The overflow guard in `ExpressiveToggleGroup` only ever measured width, so
 * at a large font scale a label could be wider than nothing and still taller than
 * the tray, and the tray — clipped to a fixed radius — cut it off. Growing the item
 * is half the fix; the other half is that the items carry M3E's `CornerFull` (half
 * their own height), so a tray that kept its radius would cut their rounded ends
 * off instead. One height, then three radii: this one, the card's, and the ring
 * between them.
 *
 * The vertical slack is the library's own padding for the item floor
 * (`ButtonDefaults.contentPaddingFor`), so this tracks what `ToggleButton` is about
 * to draw rather than guessing at it. Measured against the running app, the item
 * settles at 48dp, 51.4dp and 71.4dp at font scales 1.5, 2.0 and 3.0; the floor
 * passed to the item stays [AgentShapes.toggleItemHeight] because `ToggleButton`
 * derives its own padding from whatever height it is offered, and offering it this
 * estimate instead pushes the padding up again (72dp offered → 80dp drawn).
 */
data class ToggleTrayGeometry(
    val trayRadius: Dp,
    val hostCardRadius: Dp,
)

/** The tray and hosted-card radii for the current font scale. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@ReadOnlyComposable
fun trayGeometry(): ToggleTrayGeometry {
    val lineHeight = MaterialTheme.typography.labelLarge.lineHeight
    val lineHeightDp = if (lineHeight.isSp) {
        with(LocalDensity.current) { lineHeight.toDp() }
    } else {
        0.dp
    }
    val padding = ButtonDefaults.contentPaddingFor(AgentShapes.toggleItemHeight)
    val verticalPadding = padding.calculateTopPadding() + padding.calculateBottomPadding()
    val itemHeight = maxOf(AgentShapes.toggleItemHeight, lineHeightDp + verticalPadding)
    val trayRadius = maxOf(AgentShapes.toggleTray, itemHeight / 2 + AgentShapes.toggleTrayInset)
    return ToggleTrayGeometry(
        trayRadius = trayRadius,
        hostCardRadius = trayRadius + AgentShapes.trayHostInset,
    )
}
