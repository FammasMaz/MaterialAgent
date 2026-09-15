@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.materialagent.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.materialagent.core.model.ProviderInfo
import com.materialagent.data.HapticCue
import com.materialagent.ui.components.ExpressiveToggleGroup
import com.materialagent.ui.components.GroupDivider
import com.materialagent.ui.theme.AgentShapes
import com.materialagent.ui.theme.alphaSpec
import com.materialagent.ui.theme.colorSpec
import com.materialagent.ui.theme.scaleSpec

/**
 * The panel never grows past this.
 *
 * 332dp keeps the panel exactly under the pill (the anchor sits 60dp from the left,
 * the window is 411dp wide, so there is 339dp of room before the position provider
 * has to slide it back) while leaving the reasoning tray the width it needs. That
 * tray is the tight constraint: five levels of English in one M3E row want ~294dp at
 * the segment padding [ReasoningRow] asks for, and 332dp of panel leaves 308dp of
 * tray. The old full-screen sheet fitted the same five only because a sheet is the
 * width of the screen. See `ExpressiveToggleGroup.itemPadding` for why the padding is
 * the caller's business here rather than the library default.
 */
private val PANEL_MAX_WIDTH = 332.dp

/**
 * Height of the scrolling model list.
 *
 * This is the number that makes the panel usable at all: the list is lazy, so the
 * rows that exist are the rows that fit here plus a screenful of buffer, however
 * many thousand models the server advertises. It is deliberately not "as tall as
 * it can be" — the reasoning controls sit below it and must stay on screen without
 * scrolling, which is the property the old full-screen sheet was protecting.
 */
private val LIST_MAX_HEIGHT = 240.dp

/** Gap between the pill and the panel, and the panel's inset from the window edge. */
private val ANCHOR_GAP = 8.dp
private val WINDOW_MARGIN = 12.dp

/**
 * The model and reasoning picker: a pill in the chat header that opens a panel
 * anchored under itself.
 *
 * This replaces a modal bottom sheet, matching the chat screen in MaterialChat
 * where the current model is a button in the header and the models appear in a
 * menu under it. The sheet had one thing right and three things wrong: its
 * search-first list is what keeps a four-figure catalogue usable, and that is kept
 * here — but a sheet is a whole surface for a control the user flips between turns,
 * it hides the transcript, and it moves the header pill and the thing the pill
 * controls to opposite ends of the screen.
 *
 * What is deliberately *not* MaterialChat's: the reference renders every model in
 * a plain menu. That is fine for a dozen models and impossible for 1485, so the
 * list here is lazy over the whole catalogue, the search field filters it, and the
 * reasoning controls are pinned below it rather than scrolling away with it.
 *
 * @param current the session's model as the server reported it, qualified or bare.
 * @param running a turn is live. The server owns this setting and a change mid-turn
 *   would race the running one, so the picker is disabled while it runs — the same
 *   rule the reference picker follows.
 * @param error the last model-list failure, shown instead of a bare empty state.
 */
@Composable
fun ModelPicker(
    current: String?,
    providers: List<ProviderInfo>,
    currentReasoning: String?,
    fastEnabled: Boolean,
    running: Boolean,
    error: String?,
    onLoadModels: () -> Unit,
    onPick: (String) -> Unit,
    onReasoning: (String) -> Unit,
    onFast: (Boolean) -> Unit,
    onCue: (HapticCue) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    /*
     * The transition, not `open`, is what keeps the popup composed: the panel has
     * to outlive the tap that closed it for the exit animation to play at all. This
     * is the same gate Material's own DropdownMenu uses.
     */
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open

    /*
     * Models come from a `model.options` round trip, so they are fetched when the
     * panel is first opened rather than every time the screen is entered. Asking
     * only while the catalogue is still empty keeps a cache from the capabilities
     * screen useful, and makes this retry every time it is opened after a failure.
     */
    LaunchedEffect(open, providers.isEmpty()) {
        if (open && providers.isEmpty()) onLoadModels()
    }

    Box(modifier) {
        ModelPill(
            name = ModelSearch.displayName(current),
            expanded = open,
            enabled = !running,
            onClick = {
                onCue(HapticCue.UI_ACTION)
                open = true
            },
        )

        if (visible.currentState || visible.targetState) {
            Popup(
                popupPositionProvider = rememberAnchoredPositionProvider(),
                onDismissRequest = { open = false },
                /*
                 * Focusable so a tap outside or Back reaches this window rather than
                 * the transcript behind it, and so the search field can take the
                 * keyboard without re-anchoring anything.
                 */
                properties = PopupProperties(focusable = true),
            ) {
                ModelPanel(
                    visible = visible,
                    providers = providers,
                    current = current,
                    currentReasoning = currentReasoning,
                    fastEnabled = fastEnabled,
                    error = error,
                    /*
                     * Every change closes the panel, including the two that are not
                     * a "pick". The values are server-owned: `config.set` returns an
                     * acknowledgement and the new value only appears when the
                     * gateway pushes it back, so a panel left open would keep
                     * showing the *old* check mark and read as an ignored tap.
                     */
                    onPick = {
                        open = false
                        onPick(it)
                    },
                    onReasoning = {
                        open = false
                        onReasoning(it)
                    },
                    onFast = {
                        open = false
                        onFast(it)
                    },
                    onCue = onCue,
                )
            }
        }
    }
}

/**
 * The button the panel hangs under: the current model, then a chevron.
 *
 * A `FilledTonalButton` rather than a hand-drawn pill so the press morph is M3E's
 * own — `shapes = ButtonDefaults.shapes()` is the overload that has it, and it is
 * pinned to 32dp so the header's pill row keeps its height. The button's own
 * minimum interactive size still gives it a 48dp touch target.
 */
@Composable
private fun ModelPill(
    name: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            // Server-supplied names run long (`deepseek-v3.2-exp-thinking`); one
            // line, ellipsized, so the pill cannot grow to three.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            // The press target and the name would otherwise read as two unrelated
            // things to a screen reader; the icon carries the action.
            contentDescription = "Change model and reasoning",
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Places the panel under the anchor, against whichever window edge it would
 * otherwise cross, and above the anchor when there is no room below.
 */
@Composable
private fun rememberAnchoredPositionProvider(): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        val gap = with(density) { ANCHOR_GAP.toPx() }.toInt()
        val margin = with(density) { WINDOW_MARGIN.toPx() }.toInt()
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left
                    .coerceAtMost(windowSize.width - popupContentSize.width - margin)
                    .coerceAtLeast(margin)
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height - margin) {
                    below
                } else {
                    // Not enough room underneath: hang it above the pill, which puts
                    // the reasoning controls — the bottom of the panel — nearest
                    // the thumb.
                    (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(margin)
                }
                return IntOffset(x, y)
            }
        }
    }
}

@Composable
private fun ModelPanel(
    visible: MutableTransitionState<Boolean>,
    providers: List<ProviderInfo>,
    current: String?,
    currentReasoning: String?,
    fastEnabled: Boolean,
    error: String?,
    onPick: (String) -> Unit,
    onReasoning: (String) -> Unit,
    onFast: (Boolean) -> Unit,
    onCue: (HapticCue) -> Unit,
) {
    val all = remember(providers) { ModelSearch.options(providers) }
    var query by remember { mutableStateOf("") }
    val matches = remember(all, query) { ModelSearch.matches(all, query) }
    val selected = remember(all, current) { ModelSearch.currentSelection(all, current) }
    val origin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
        TransformOrigin(0f, 0f)
    } else {
        TransformOrigin(1f, 0f)
    }

    AnimatedVisibility(
        visibleState = visible,
        // Spatial enter/exit for the grow, effects for the fade: a scale that
        // overshoots reads as a physical object arriving, a colour that does not
        // bounce reads as a bug. Both follow the theme's motion scheme, so reduced
        // motion flattens this without a branch here.
        enter = fadeIn(alphaSpec()) +
            scaleIn(animationSpec = scaleSpec(), initialScale = 0.9f, transformOrigin = origin),
        exit = fadeOut(alphaSpec()) +
            scaleOut(animationSpec = scaleSpec(), targetScale = 0.9f, transformOrigin = origin),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = PANEL_MAX_WIDTH),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                when {
                    all.isEmpty() -> ModelListStatus(error)

                    else -> {
                        if (ModelSearch.showsSearchField(all.size)) {
                            SearchField(
                                query = query,
                                onQueryChange = { query = it },
                                total = all.size,
                            )
                        }
                        if (matches.isEmpty()) {
                            NoMatch(query)
                        } else {
                            ModelList(
                                options = matches,
                                selected = selected,
                                onPick = {
                                    onCue(HapticCue.TOGGLE)
                                    onPick(it)
                                },
                            )
                        }
                    }
                }

                GroupDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

                ReasoningRow(
                    current = currentReasoning,
                    onSelect = {
                        onCue(HapticCue.TOGGLE)
                        onReasoning(it)
                    },
                )
                FastRow(
                    enabled = fastEnabled,
                    onChange = {
                        onCue(HapticCue.TOGGLE)
                        onFast(it)
                    },
                )
            }
        }
    }
}

/**
 * The list itself: lazy over every match, so the composed rows are the visible ones.
 *
 * A `DropdownMenu` would have been the one-liner here and is exactly what the old
 * eager version looked like before its column of 1485 items pushed the reasoning
 * controls off the sheet. This keeps the two properties that cost something to
 * learn: bounded rendering, and controls that do not scroll away.
 */
@Composable
private fun ModelList(
    options: List<ModelOption>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = LIST_MAX_HEIGHT),
    ) {
        items(options, key = { it.qualified }) { option ->
            ModelRow(
                option = option,
                isSelected = option.qualified == selected,
                onClick = { onPick(option.qualified) },
            )
        }
    }
}

/**
 * One model row.
 *
 * The selected row is marked twice — a tinted container and a check — because a
 * colour on its own is not a state a colour-blind user can read, and the name is
 * tinted `primary` in the emphasized weight the way the reference picker does it.
 * Both colours move on the effects spec: they are neither spatial nor allowed to
 * overshoot.
 */
@Composable
private fun ModelRow(
    option: ModelOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val nameColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = colorSpec(),
        label = "modelNameColor",
    )
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        },
        animationSpec = colorSpec(),
        label = "modelRowContainer",
    )

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = nameColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = option.model,
                // M3E's emphasized style for the selected name, rather than a hand-
                // rolled `FontWeight.SemiBold` on the baseline style: the type scale
                // already owns "one step heavier", and it keeps the token the same
                // place the rest of the app's emphasis comes from.
                style = if (isSelected) {
                    MaterialTheme.typography.bodyMediumEmphasized
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // Which server this model comes from, on the same line: the same model
            // name is offered by more than one provider, and a second line per row
            // would halve the number of rows a 240dp list can show.
            Text(
                text = option.provider,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 104.dp),
            )
            if (isSelected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Current model",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    total: Int,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search $total models") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = AgentShapes.pill,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Reasoning effort, as the app's fused selector rather than a row of loose chips. */
@Composable
private fun ReasoningRow(
    current: String?,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            text = "Reasoning",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        ExpressiveToggleGroup(
            options = ModelSearch.REASONING_LEVELS,
            // Nothing selected when the session reports no effort — which is a real
            // state, not "minimal".
            selectedIndex = ModelSearch.REASONING_LEVELS.indexOf(current.orEmpty()),
            onSelect = { index -> onSelect(ModelSearch.REASONING_LEVELS[index]) },
            fillWidth = true,
            // Five longish labels, one narrow popover: at the library's default
            // segment padding this row would scroll, and the level the session is
            // running at — `max`, the one that matters most — would be the one hidden
            // off the right edge. Tighter segments keep all five visible, which is
            // what the full-width sheet managed before this became a popover.
            itemPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FastRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Fast tier",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

/**
 * What the panel says while the catalogue is empty.
 *
 * The old sheet had one message for this ("pull the server's capabilities"), which
 * is the wrong advice when a fetch has just failed and the reason is sitting in the
 * repository. The spinner is only for the in-flight case: an empty list that is
 * still loading and an empty list after a failure look identical from here, and a
 * spinner that never stops is worse than a sentence that is honest.
 */
@Composable
private fun ModelListStatus(error: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error == null) {
            // The M3E loading indicator rather than a 16dp circular spinner: it is
            // the sanctioned replacement for indeterminate circular progress and, at
            // this size, its polygon morph still reads as motion.
            LoadingIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = error ?: "Loading the server's models…",
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun NoMatch(query: String) {
    Text(
        text = "No model matches \"$query\".",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
