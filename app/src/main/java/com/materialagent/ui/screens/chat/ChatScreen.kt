package com.materialagent.ui.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.core.model.ProviderInfo
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.EntryKind
import com.materialagent.data.chat.TranscriptEntry
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.components.AgentOrb
import com.materialagent.ui.components.EmptyState
import com.materialagent.ui.components.ErrorBanner
import com.materialagent.ui.components.LoadingBlock
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.NoticeBanner
import com.materialagent.ui.containerViewModel
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.LocalSendOnEnter
import com.materialagent.ui.theme.ExpressiveMotion
import com.materialagent.ui.theme.LocalShowReasoning
import com.materialagent.ui.theme.LocalShowToolCalls
import com.materialagent.ui.theme.LocalStreamingHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One conversation.
 *
 * The screen is a transcript plus a composer and nothing else — every bit of
 * running state (working, waiting on an approval, interrupted) is expressed in the
 * transcript itself rather than in a separate status page, because that is where
 * the user's attention already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    app: AgentViewModel,
    storedId: String?,
    onBack: () -> Unit,
    onSwitchTo: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = containerViewModel { ChatViewModel(it, storedId) }
    val cue = rememberCue()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val connection by app.status.collectAsStateWithLifecycle()
    val showReasoning = LocalShowReasoning.current
    val showTools = LocalShowToolCalls.current
    val streamingHaptics = LocalStreamingHaptics.current

    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var modelSheetOpen by remember { mutableStateOf(false) }

    // Semantic haptics for the whole turn, filtered by the user's preference.
    LaunchedEffect(Unit) {
        app.chat.cues.collect { hapticCue ->
            if (hapticCue == HapticCue.STREAM_TICK && !streamingHaptics) return@collect
            cue(hapticCue)
        }
    }

    val listState = rememberLazyListState()
    val entries = transcript.visibleEntries
    val lastEntrySignature = entries.lastOrNull()?.let { entry ->
        entry.id.hashCode() + entry.text.length + entry.reasoning.length + (entry.tool?.result?.length ?: 0)
    } ?: 0
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(entries.size, lastEntrySignature) {
        if (entries.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    // "Working for 2m 14s" — a turn that takes minutes should say so.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transcript.running) {
        while (transcript.running) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = transcript.turnStartedAt?.let { start ->
        ((now / 1000.0) - start).coerceAtLeast(0.0)
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            ChatTopBar(
                title = transcript.title.ifBlank {
                    if (storedId == null) "New conversation" else "Conversation"
                },
                entry = transcript.info,
                running = transcript.running,
                elapsed = elapsed,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onPickModel = {
                    menuOpen = false
                    modelSheetOpen = true
                    viewModel.loadModelOptions()
                },
                onInterrupt = {
                    menuOpen = false
                    cue(HapticCue.INTERRUPTED)
                    viewModel.interrupt()
                },
                onBranch = {
                    menuOpen = false
                    viewModel.branch { newId -> onSwitchTo(newId) }
                },
                onStatus = {
                    menuOpen = false
                    viewModel.loadStatus()
                },
                onSettings = {
                    menuOpen = false
                    onOpenSettings()
                },
            )

            val failed = connection as? ConnectionStatus.Failed
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (failed != null) {
                    ErrorBanner(
                        message = failed.message,
                        onDismiss = { app.dismissProblem() },
                        onRetry = app::retry,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                notice?.let { message ->
                    NoticeBanner(
                        message = message,
                        onDismiss = viewModel::dismissNotice,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                statusText?.let { text ->
                    NoticeBanner(
                        message = text,
                        onDismiss = viewModel::clearStatus,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    transcript.loadingHistory && entries.isEmpty() ->
                        LoadingBlock("Reopening the conversation…")

                    transcript.historyError != null && entries.isEmpty() -> EmptyState(
                        title = "Could not open this conversation",
                        body = transcript.historyError ?: "",
                        actionLabel = "Go back",
                        onAction = onBack,
                    )

                    entries.isEmpty() -> EmptyState(
                        title = app.skin.value?.branding?.welcome?.ifBlank { null }
                            ?: "What should we get done?",
                        body = "Ask for something concrete. Your agent works in its own " +
                            "workspace with its own tools — you will see each step as it runs.",
                        orbSize = 88.dp,
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        entries.forEach { entry ->
                            item(key = entry.id) {
                                Box(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = spring(
                                            dampingRatio = 1f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                        // A streaming row changes height on almost every
                                        // frame; animating its placement fights the
                                        // auto-scroll and reads as vertical jitter.
                                        placementSpec = if (entry.isStreaming) {
                                            null
                                        } else {
                                            spring(
                                                dampingRatio = 1f,
                                                stiffness = Spring.StiffnessMediumLow,
                                            )
                                        },
                                        // Instant removal, so a regenerated turn never
                                        // leaves a ghost of its old text behind.
                                        fadeOutSpec = null,
                                    ),
                                ) {
                                    TranscriptRow(
                                        entry = entry,
                                        showReasoning = showReasoning,
                                        showTools = showTools,
                                        onAnswer = { value, permanent ->
                                            entry.interactive?.let { request ->
                                                viewModel.answer(request, value, permanent)
                                            }
                                        },
                                        onCue = cue,
                                    )
                                }
                            }
                        }
                        if (transcript.running) {
                            item(key = "running-indicator") {
                                WorkingIndicator(elapsed = elapsed)
                            }
                        }
                    }
                }

                if (!atBottom && entries.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = {
                            cue(HapticCue.SENT)
                            scope.launch { listState.animateScrollToItem(entries.size - 1) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Jump to latest")
                    }
                }
            }

            Composer(
                draft = draft,
                running = transcript.running,
                sending = sending,
                enabled = connection !is ConnectionStatus.Failed,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send,
                onSteer = viewModel::steer,
                onStop = {
                    cue(HapticCue.INTERRUPTED)
                    viewModel.interrupt()
                },
            )
        }
    }

    if (modelSheetOpen) {
        ModelPickerSheet(
            onDismiss = { modelSheetOpen = false },
            providers = providers,
            current = transcript.info?.model,
            onPick = { qualified ->
                viewModel.setModel(qualified)
                modelSheetOpen = false
            },
            onReasoning = { effort ->
                viewModel.setReasoning(effort)
                modelSheetOpen = false
            },
            onFast = { enabled ->
                viewModel.setFast(enabled)
                modelSheetOpen = false
            },
            currentReasoning = transcript.info?.reasoningEffort,
            fastEnabled = transcript.info?.fast == true,
        )
    }
}

/** Dispatches one transcript entry to the right row renderer. */
@Composable
private fun TranscriptRow(
    entry: TranscriptEntry,
    showReasoning: Boolean,
    showTools: Boolean,
    onAnswer: (String, Boolean) -> Unit,
    onCue: (HapticCue) -> Unit,
) {
    when (entry.kind) {
        EntryKind.USER -> UserBubble(text = entry.text, timestamp = entry.timestamp)

        EntryKind.ASSISTANT -> AssistantBlock(
            entry = entry,
            showReasoning = showReasoning,
        )

        EntryKind.TOOL -> {
            if (showTools) {
                entry.tool?.let { ToolCard(it) }
            }
        }

        EntryKind.NOTE -> NoteRow(entry.text)

        EntryKind.TODOS -> TodosCard(entry.todos)

        EntryKind.APPROVAL, EntryKind.CLARIFY, EntryKind.SUDO, EntryKind.SECRET ->
            entry.interactive?.let { request ->
                InteractionCard(request = request, onAnswer = onAnswer, onCue = onCue)
            }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    entry: com.materialagent.core.model.SessionInfo?,
    running: Boolean,
    elapsed: Double?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPickModel: () -> Unit,
    onInterrupt: () -> Unit,
    onBranch: () -> Unit,
    onStatus: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (running) {
                        MetaPill(
                            text = elapsed?.let { "Working ${formatDuration(it)}" } ?: "Working",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            icon = Icons.Rounded.AutoAwesome,
                        )
                    }
                    entry?.model?.let { model ->
                        MetaPill(text = model.substringAfterLast('/'))
                    }
                    // The effort pill is the least informative of the three and
                    // the header only has room for so much; while a turn runs it
                    // steps aside for the live timer.
                    if (!running) {
                        entry?.reasoningEffort?.takeIf { it.isNotBlank() && it != "none" }?.let {
                            MetaPill(text = it)
                        }
                    }
                }
            }

            IconButton(onClick = onPickModel) {
                Icon(Icons.Rounded.Tune, contentDescription = "Model and reasoning")
            }

            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    if (running) {
                        DropdownMenuItem(
                            text = { Text("Stop the agent") },
                            leadingIcon = { Icon(Icons.Rounded.Stop, contentDescription = null) },
                            onClick = onInterrupt,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Branch from here") },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null)
                        },
                        onClick = onBranch,
                    )
                    DropdownMenuItem(
                        text = { Text("Server status") },
                        leadingIcon = { Icon(Icons.Rounded.Psychology, contentDescription = null) },
                        onClick = onStatus,
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = onSettings,
                    )
                }
            }
        }
    }
}

/** A live "still working" row that ticks, so a long turn never looks stuck. */
@Composable
private fun WorkingIndicator(elapsed: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentOrb(size = 28.dp, active = true)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Agent is working",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (elapsed != null) {
                Text(
                    text = "for ${formatDuration(elapsed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The composer.
 *
 * The action button changes *meaning* with the turn state — send, steer, or stop —
 * and morphs its shape to match. Steering is offered only when there is a running
 * turn and text in the box, which is exactly when it is useful and never otherwise.
 */
@Composable
private fun Composer(
    draft: String,
    running: Boolean,
    sending: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending && enabled
    val steering = running && draft.isNotBlank()
    val sendOnEnter = LocalSendOnEnter.current

    val targetShape = when {
        steering -> 20.dp
        running -> 20.dp
        else -> 26.dp
    }
    val shape by animateDpAsState(
        targetValue = targetShape,
        animationSpec = ExpressiveMotion.Specs.cornerRadius,
        label = "composerShape",
    )
    val container by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = ExpressiveMotion.Specs.color,
        label = "composerContainer",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(
            topStart = shape + 10.dp,
            topEnd = shape + 10.dp,
            bottomStart = shape,
            bottomEnd = shape,
        ),
        color = container,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = {
                    Text(
                        if (running) "Steer the agent…" else "Message your agent…",
                        maxLines = 1,
                    )
                },
                maxLines = 6,
                enabled = enabled,
                // With send-on-enter the IME action button sends; otherwise the
                // field stays a plain multi-line editor and Enter adds a line.
                keyboardOptions = KeyboardOptions(
                    imeAction = if (sendOnEnter && !running) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSend) onSend() },
                ),
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(4.dp))

            AnimatedContent(
                targetState = when {
                    steering -> ComposerAction.STEER
                    running -> ComposerAction.STOP
                    else -> ComposerAction.SEND
                },
                transitionSpec = {
                    (scaleIn(animationSpec = ExpressiveMotion.Specs.playful) togetherWith
                        scaleOut(animationSpec = ExpressiveMotion.Specs.playful))
                },
                label = "composerAction",
            ) { action ->
                when (action) {
                    ComposerAction.SEND -> FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Send message",
                        )
                    }

                    ComposerAction.STEER -> Button(
                        onClick = onSteer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(Icons.Rounded.AltRoute, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Steer")
                    }

                    ComposerAction.STOP -> FilledTonalIconButton(
                        onClick = onStop,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Stop the agent")
                    }
                }
            }
        }
    }
}

private enum class ComposerAction { SEND, STEER, STOP }

/** One row of the model picker: `qualified` is what gets sent to the server. */
private data class ModelChoice(
    val qualified: String,
    val model: String,
    val provider: String,
)

/** Models, reasoning effort and the fast tier — all per session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    providers: List<ProviderInfo>,
    current: String?,
    currentReasoning: String?,
    fastEnabled: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onReasoning: (String) -> Unit,
    onFast: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (providers.isEmpty()) {
                Text(
                    text = "No models reported. Pull the server's capabilities from the Agent tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // A busy server advertises well over a thousand models. A flat column
            // of them would be both slow to compose and would push the Reasoning
            // section past the bottom of the sheet, so this is a search box over a
            // height-bounded lazy list, with the unfiltered view capped to a short
            // first page.
            var query by remember { mutableStateOf("") }
            val all = remember(providers) {
                providers.flatMap { provider ->
                    val providerName = provider.name.ifBlank { provider.slug }
                    provider.models.map { model ->
                        ModelChoice("${provider.slug}/$model", model, providerName)
                    }
                }
            }
            val matches = remember(query, all) {
                val needle = query.trim().lowercase()
                if (needle.isEmpty()) {
                    all
                } else {
                    all.filter {
                        it.qualified.lowercase().contains(needle) ||
                            it.provider.lowercase().contains(needle)
                    }
                }
            }
            val shown = matches.take(if (query.isBlank()) 25 else 120)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${all.size} models") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            if (shown.isEmpty()) {
                Text(
                    text = "No model matches \"$query\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.qualified }) { choice ->
                        ListItem(
                            headlineContent = { Text(choice.model) },
                            supportingContent = { Text(choice.provider) },
                            trailingContent = {
                                if (current == choice.model || current == choice.qualified) {
                                    MetaPill(
                                        text = "Current",
                                        container = MaterialTheme.colorScheme.primaryContainer,
                                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onPick(choice.qualified) },
                        )
                    }
                }
                if (matches.size > shown.size) {
                    Text(
                        text = "Showing ${shown.size} of ${matches.size} — keep typing to narrow.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = "Reasoning",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "max" is a real level on this server and was missing here, so a
                // session running at max showed no selection at all.
                listOf("minimal", "low", "medium", "high", "max").forEach { effort ->
                    val selected = currentReasoning == effort
                    Surface(
                        onClick = { onReasoning(effort) },
                        shape = RoundedCornerShape(50),
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.alpha(if (selected) 1f else 0.85f),
                    ) {
                        Text(
                            text = effort,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            TextButton(
                onClick = { onFast(!fastEnabled) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(if (fastEnabled) "Fast tier: on" else "Fast tier: off")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
