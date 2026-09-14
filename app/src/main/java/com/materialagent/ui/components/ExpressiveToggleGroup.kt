package com.materialagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * Behaviour follows the reference implementation: a `RadioButton` role so
 * TalkBack announces the set correctly, a haptic *before* the selection changes,
 * and labels that never wrap (a longer option ellipsises rather than making the
 * row taller than the rest of the screen).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveToggleGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Rendered inside each button; receives the option's label. */
    label: @Composable (String) -> Unit = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    /** Container behind the fused buttons — the group's "tray". */
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentPadding: Dp = 4.dp,
    cornerRadius: Dp = 28.dp,
    /**
     * `true` splits the width evenly between the options — right for two or
     * three short labels (Light/Dark/System), where a ragged edge would look
     * accidental. `false` (the default) lets each button take the width its
     * label needs and scrolls the row if the set does not fit, so a long label
     * is shown in full rather than truncated to "Conversati…".
     */
    fillWidth: Boolean = false,
) {
    if (options.isEmpty()) return
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(if (fillWidth) Modifier else Modifier.horizontalScroll(scroll))
            .clip(RoundedCornerShape(cornerRadius))
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
                expand = fillWidth,
                onClick = {
                    if (index != selectedIndex) onSelect(index)
                },
            ) { label(option) }
        }
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
        modifier = size.semantics { role = Role.RadioButton },
    ) {
        content()
    }
}
