package com.materialagent.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable

/**
 * Names an icon-only control on long-press.
 *
 * Material 3 pairs an icon button with a tooltip because a glyph on its own is a
 * guess. The label the icon already carries is what a screen reader announces, but
 * a sighted user only meets it by long-pressing, which is the point of this.
 *
 * Only controls whose meaning is genuinely not self-evident get one: a back arrow
 * needs no help, a row of three dots, a chevron that hides a panel, and an arrow
 * pointing down into a running transcript do.
 *
 * @param label the same words the wrapped icon passes as its `contentDescription`,
 *   so the tooltip and the accessible name cannot drift apart in meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
