package com.materialagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite

import com.materialagent.ui.theme.AgentShapes

/**
 * The expressive single-choice selector: one row of `ToggleButton`s whose shapes
 * are made to fit together, so a set of mutually exclusive options reads as a
 * single object instead of a row of separate chips.
 *
 * This is Material 3 Expressive's own answer to a segmented control, and the
 * connected shapes are the point of it — `ButtonGroupDefaults` hands back the
 * leading/middle/trailing outlines and the row is laid out with
 * `ConnectedSpaceBetween`, which lets them butt up against each other with the
 * shared edge falling away only on the selected item. `SegmentedButton` (the
 * older Material 3 component) draws separate outlines for each item and loses
 * that fusion, so it is deliberately not used for enumerated preferences.
 *
 * Behaviour follows the reference implementation: a `RadioButton` role so TalkBack
 * announces the set correctly, no selection change (and no cue from the caller) when
 * the segment is already chosen, and labels that are measured rather than trusted —
 * see [splitWouldClip].
 *
 * The tray and the items are one geometry, not two guesses: every item is pinned to
 * [AgentShapes.toggleItemHeight] and the tray's radius comes from
 * [AgentShapes.toggleTray], which is that height's full corner plus the inset. See
 * those two tokens for why a free-hand radius makes the ring thick at the corners
 * and thin along the edges.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveToggleGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Rendered inside each button; receives the option's label and its checked state. */
    label: @Composable (String, Boolean) -> Unit = { text, checked ->
        Text(
            text = text,
            // A selected segment is a selection state, which is what the emphasized
            // voice is for; the rest of the row keeps the baseline label style.
            style = if (checked) {
                MaterialTheme.typography.labelLargeEmphasized
            } else {
                MaterialTheme.typography.labelLarge
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    /** Container behind the fused buttons — the group's "tray". */
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentPadding: Dp = AgentShapes.toggleTrayInset,
    /**
     * Propose an even split across the tray — right for two or three short labels,
     * where a ragged edge would look accidental. It is a proposal rather than an
     * instruction: the group measures first and falls back to a scrolling row when
     * the split would clip a label, so "Off/Subtle/Normal/Strong" at a large font
     * scale scrolls instead of truncating to "Stro…".
     */
    fillWidth: Boolean = false,
) {
    if (options.isEmpty()) return
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        val splitEvenly = fillWidth && !splitWouldClip(options, maxWidth, contentPadding)
        Row(
            modifier = Modifier
                .then(if (splitEvenly) Modifier.fillMaxWidth() else Modifier)
                .then(if (splitEvenly) Modifier else Modifier.horizontalScroll(scroll))
                .clip(RoundedCornerShape(AgentShapes.toggleTray))
                .background(containerColor)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, option ->
                val shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
                // A lone option is both leading and trailing; the middle shape is the
                // one that reads as a single pill rather than a half-open outline.
                val single = options.size == 1
                ToggleGroupItem(
                    checked = index == selectedIndex,
                    shapes = if (single) ButtonGroupDefaults.connectedMiddleButtonShapes() else shapes,
                    expand = splitEvenly,
                    onClick = {
                        if (index != selectedIndex) onSelect(index)
                    },
                ) { label(option, index == selectedIndex) }
            }
        }
    }
}

/**
 * Whether an even split of [options] across [available] width would clip any label.
 *
 * Both label styles are measured — the selected item wears the emphasized one, which
 * is heavier and therefore wider — and the item's own inset inside the button is the
 * library's own token for this button height, so neither the threshold nor the padding
 * is a second guess at what [ToggleButton] is about to draw.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun splitWouldClip(options: List<String>, available: Dp, contentPadding: Dp): Boolean {
    if (!available.isFinite) return true
    val spacing = ButtonGroupDefaults.ConnectedSpaceBetween * (options.size - 1)
    val perItem = (available - contentPadding * 2 - spacing) / options.size
    val density = LocalDensity.current
    val inset = with(density) {
        val padding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight)
        with(LocalLayoutDirection.current) {
            padding.calculateLeftPadding(this) + padding.calculateRightPadding(this)
        }.toPx()
    }
    val perItemPx = with(density) { perItem.toPx() }
    val measurer = rememberTextMeasurer()
    val styles = listOf(MaterialTheme.typography.labelLarge, MaterialTheme.typography.labelLargeEmphasized)
    return options.any { option ->
        styles.maxOf { style ->
            measurer.measure(AnnotatedString(option), style = style, maxLines = 1).size.width
        } + inset > perItemPx
    }
}

/**
 * One item of an [ExpressiveToggleGroup]. Split out so the group can stay a
 * layout and this can stay a control, and so a caller that needs an icon-only
 * or icon+label item can reuse it without re-deriving the shapes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.ToggleGroupItem(
    checked: Boolean,
    shapes: ToggleButtonShapes,
    expand: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val size = if (expand) Modifier.weight(1f).fillMaxWidth() else Modifier
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        shapes = shapes,
        // An explicit height, not just a minimum: without it `ToggleButton` draws
        // M3E's 40dp container inside a 48dp touch target, and those 8dp of slack
        // make the tray's ring 8dp top and bottom against 4dp at the sides — so no
        // single tray radius can sit concentrically with both. Pinning the box to
        // the height the tray is built around keeps the ring even on all four sides.
        modifier = size
            .height(AgentShapes.toggleItemHeight)
            .semantics { role = Role.RadioButton },
    ) {
        content()
    }
}
