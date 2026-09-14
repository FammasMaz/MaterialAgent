package com.materialagent.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.core.model.OutgoingAttachment
import com.materialagent.core.model.ProviderInfo
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.data.describeAttachment
import com.materialagent.data.chat.EntryKind
import com.materialagent.data.chat.TranscriptEntry
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.components.AgentArt
import com.materialagent.ui.components.EmptyState
import com.materialagent.ui.components.ErrorBanner
import com.materialagent.ui.components.LoadingBlock
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.media.LocalMediaEnvironment
import com.materialagent.ui.components.media.PendingAttachmentsBubble
import com.materialagent.ui.components.media.rememberMediaEnvironment
import com.materialagent.ui.theme.AgentShapes
import com.materialagent.ui.components.NoticeBanner
import com.materialagent.ui.containerViewModel
import com.materialagent.ui.rememberContainer
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.LocalSendOnEnter
import com.materialagent.ui.theme.alphaSpec
import com.materialagent.ui.theme.cornerRadiusSpec
import com.materialagent.ui.components.pullToReveal
import com.materialagent.ui.components.PullRatchet
import com.materialagent.ui.components.rememberPullRevealState
import com.materialagent.ui.components.scrollHaptics
import com.materialagent.ui.theme.placementSpec
import com.materialagent.ui.theme.playfulSpec
import com.materialagent.ui.theme.LocalScrollHaptics
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    app: AgentViewModel,
    storedId: String?,
    onBack: () -> Unit,
    onSwitchTo: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = containerViewModel { ChatViewModel(it) }
    val cue = rememberCue()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val modelError by viewModel.modelError.collectAsStateWithLifecycle()
    val pendingFiles by viewModel.attachments.collectAsStateWithLifecycle()
    val connection by app.status.collectAsStateWithLifecycle()
    val showReasoning = LocalShowReasoning.current
    val showTools = LocalShowToolCalls.current
    val streamingHaptics = LocalStreamingHaptics.current
    val scrollHaptics = LocalScrollHaptics.current

    /*
     * Media rows fetch their own bytes, so they need the signed-in server and
     * the app's shared HTTP client — the jar on that client is what carries the
     * sign-in the gateway demands. This screen is the composition root for the
     * transcript, so it builds that environment once and publishes it below,
     * rather than threading a transport through every row renderer between here
     * and an attachment.
     */
    val container = rememberContainer()
    val mediaEnv = rememberMediaEnvironment(connection.baseUrlOrNull(), container.http)

    // Keeps the screen pointed at whatever session the route names, including a
    // switch to a freshly branched sibling that lands on this same destination.
    LaunchedEffect(storedId) { viewModel.openIfNeeded(storedId, title = null) }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var infoSheetOpen by remember { mutableStateOf(false) }

    // The stored-list row for this conversation, when there is one. It is the only
    // source for source/message count/start time: `session.info` does not carry
    // them, and the inbox already caches the row.
    val storedSessions by container.sessions.sessions.collectAsStateWithLifecycle()
    val summary = remember(transcript.storedSessionId, storedSessions) {
        storedSessions.firstOrNull { it.id == transcript.storedSessionId }
    }

    /*
     * Three pickers, because "attach a file" means three different things on a
     * phone. The photo picker is the platform's own gallery UI; the audio one is
     * narrowed to audio MIME types so a voice note cannot be answered with a
     * 40-minute video; documents take anything, which is the honest default for a
     * catch-all.
     *
     * All three hand back a `content://` URI, so all three describe it the same
     * way — the display name and size only exist in the provider's metadata, and
     * a cancelled pick still calls back with null.
     */
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.attach(uri?.let { describeAttachment(context, it) })
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        viewModel.attach(uri?.let { describeAttachment(context, it) })
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.attach(uri?.let { describeAttachment(context, it) })
    }

    // Semantic haptics for the whole turn, filtered by the user's preference.
    LaunchedEffect(Unit) {
        app.chat.cues.collect { hapticCue ->
            if (hapticCue == HapticCue.STREAM_TICK && !streamingHaptics) return@collect
            cue(hapticCue)
        }
    }

    val listState = rememberLazyListState()
    /*
     * The pull past the top of the transcript. One state for the screen: the
     * panel's height, the latched-open flag and the settle animation all read
     * from it, and nothing else animates — a second size animation over the same
     * subtree is what made the navigation pill's neighbours snap.
     */
    val reveal = rememberPullRevealState()
    val revealSpec = placementSpec<Float>()

    /*
     * The pull's own feedback, read off the pull itself rather than off the
     * scroll deltas that move it: one ratchet tick per tenth of the way to
     * giving, then a single REVEAL when the threshold is reached. Reading it as
     * state is what keeps a streaming answer's auto-scroll silent — that scroll
     * is a side effect, arrives as such, and never moves the pull at all.
     */
    LaunchedEffect(reveal) {
        val ratchet = PullRatchet()
        snapshotFlow { reveal.progress }.collect { progress ->
            ratchet.onProgress(progress)?.let(cue)
        }
    }
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
                .imePadding()
                // The reveal owns the top of the transcript. The pull is attached
                // here, above both the panel and the list, so it sees the drag the
                // list could not use and the release that settles it; a nested
                // scroll connection is only ever consulted by a scrollable below it.
                .pullToReveal(
                    state = reveal,
                    atTop = {
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                    },
                    settleSpec = revealSpec,
                ),
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
                providers = providers,
                modelError = modelError,
                onLoadModels = { viewModel.loadModelOptions() },
                onPickModel = { viewModel.setModel(it) },
                onReasoning = { viewModel.setReasoning(it) },
                onFast = { viewModel.setFast(it) },
                onCue = cue,
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
                onInfo = {
                    menuOpen = false
                    cue(HapticCue.UI_ACTION)
                    infoSheetOpen = true
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

            PullRevealPanel(
                state = reveal,
                transcript = transcript,
                summary = summary,
                onCue = cue,
                onCollapse = {
                    cue(HapticCue.UI_ACTION)
                    reveal.collapse(revealSpec)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

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

                    // The empty state keeps a scroll node of its own so the pull
                    // still has somewhere to be reported from — and shares the
                    // transcript's list state, because it *is* the transcript, just
                    // one with nothing in it yet. Hermes only persists a session
                    // after its first turn, so this is where a session's ids and
                    // model are still worth looking at.
                    entries.isEmpty() -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                    ) {
                        item {
                            EmptyState(
                                title = app.skin.value?.branding?.welcome?.ifBlank { null }
                                    ?: "What should we get done?",
                                body = "Ask for something concrete. Your agent works in its own " +
                                    "workspace with its own tools — you will see each step as it runs.",
                                artSize = 88.dp,
                            )
                        }
                    }

                    else -> CompositionLocalProvider(
                        LocalMediaEnvironment provides mediaEnv,
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                // Ticks under the finger, never for the auto-scroll that
                                // follows a streaming answer.
                                .scrollHaptics(scrollHaptics),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            entries.forEach { entry ->
                                item(key = entry.id) {
                                    Box(
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = alphaSpec(),
                                            // A streaming row changes height on almost every
                                            // frame; animating its placement fights the
                                            // auto-scroll and reads as vertical jitter.
                                            placementSpec = if (entry.isStreaming) {
                                                null
                                            } else {
                                                placementSpec()
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
                                            onAnswer = { value ->
                                                entry.interactive?.let { request ->
                                                    viewModel.answer(request, value)
                                                }
                                            },
                                            onAnswerQuestion = { questionId, value ->
                                                entry.interactive?.let { request ->
                                                    viewModel.answer(request, value, questionId)
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
                }

                if (!atBottom && entries.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = {
                            cue(HapticCue.SENT)
                            scope.launch { listState.animateScrollToItem(entries.size - 1) }
                        },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Jump to latest")
                    }
                }
            }

            PendingAttachmentsBubble(
                attachments = pendingFiles,
                onRemove = viewModel::removeAttachment,
                modifier = Modifier.padding(bottom = 2.dp),
            )

            Composer(
                draft = draft,
                running = transcript.running,
                sending = sending,
                enabled = connection !is ConnectionStatus.Failed,
                attachments = pendingFiles,
                onDraftChange = viewModel::updateDraft,
                onAttach = { source ->
                    when (source) {
                        AttachSource.PHOTO -> photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )

                        AttachSource.AUDIO -> audioPicker.launch("audio/*")
                        AttachSource.DOCUMENT -> documentPicker.launch(arrayOf("*/*"))
                    }
                },
                onSend = {
                    // The one interaction the user repeats all day, and the one that
                    // most needs an answer: "the app took it".
                    cue(HapticCue.SENT)
                    viewModel.send()
                    // Answering and reading both want the transcript, and a
                    // blocking card can appear immediately after a send — the
                    // keyboard would sit on top of it.
                    focusManager.clearFocus()
                },
                onSteer = {
                    // Steering is a send that the running turn will pick up, so it
                    // gets the same acknowledgement rather than silence.
                    cue(HapticCue.SENT)
                    viewModel.steer()
                },
                onStop = {
                    cue(HapticCue.INTERRUPTED)
                    viewModel.interrupt()
                },
            )
        }
    }

    if (infoSheetOpen) {
        SessionInfoSheet(
            transcript = transcript,
            summary = summary,
            onCue = cue,
            onDismiss = { infoSheetOpen = false },
        )
    }

}

/** Dispatches one transcript entry to the right row renderer. */
@Composable
private fun TranscriptRow(
    entry: TranscriptEntry,
    showReasoning: Boolean,
    showTools: Boolean,
    onAnswer: (String) -> Unit,
    onAnswerQuestion: (questionId: String, value: String) -> Unit,
    onCue: (HapticCue) -> Unit,
) {
    when (entry.kind) {
        EntryKind.USER -> UserBubble(
            text = entry.text,
            timestamp = entry.timestamp,
            attachments = entry.attachments,
        )

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
                InteractionCard(
                    request = request,
                    onAnswer = onAnswer,
                    onCue = onCue,
                    onAnswerQuestion = onAnswerQuestion,
                )
            }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatTopBar(
    title: String,
    entry: com.materialagent.core.model.SessionInfo?,
    running: Boolean,
    elapsed: Double?,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    providers: List<ProviderInfo>,
    modelError: String?,
    onLoadModels: () -> Unit,
    onPickModel: (String) -> Unit,
    onReasoning: (String) -> Unit,
    onFast: (Boolean) -> Unit,
    onCue: (HapticCue) -> Unit,
    onInterrupt: () -> Unit,
    onBranch: () -> Unit,
    onStatus: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
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
                    /*
                     * The model pill *is* the picker's trigger: tapping it opens the
                     * panel anchored under itself. It is drawn even before the server
                     * has reported a model, so there is no state in which the picker
                     * cannot be reached — which is why the icon button that used to
                     * open the sheet is gone rather than sitting beside it.
                     */
                    ModelPicker(
                        current = entry?.model,
                        providers = providers,
                        currentReasoning = entry?.reasoningEffort,
                        fastEnabled = entry?.fast == true,
                        running = running,
                        error = modelError,
                        onLoadModels = onLoadModels,
                        onPick = onPickModel,
                        onReasoning = onReasoning,
                        onFast = onFast,
                        onCue = onCue,
                        // The model name is the one token here that can be arbitrarily
                        // long, and this row has no room to grow: let it take what is
                        // left and ellipsize rather than push the other pills past the
                        // header's edge.
                        modifier = Modifier.weight(1f, fill = false),
                    )
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

            Box {
                IconButton(onClick = { onMenuOpenChange(true) }, shapes = IconButtonDefaults.shapes()) {
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
                        text = { Text("Conversation info") },
                        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                        onClick = onInfo,
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
        AgentArt(size = 28.dp, active = true)
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    draft: String,
    running: Boolean,
    sending: Boolean,
    enabled: Boolean,
    attachments: List<OutgoingAttachment>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onStop: () -> Unit,
    onAttach: (AttachSource) -> Unit,
) {
    // Files count as something to send: a photo with no caption is a complete
    // message, and gating the send button on the text alone made an attached
    // picture look unsendable.
    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !sending && enabled
    val steering = running && draft.isNotBlank()
    val sendOnEnter = LocalSendOnEnter.current

    // Hoisted: `AnimatedContent`'s `transitionSpec` is not a composable lambda, so
    // the scheme reading has to happen out here.
    val morphSpec = playfulSpec<Float>()

    val targetShape = when {
        steering -> AgentShapes.composerActive
        running -> AgentShapes.composerActive
        else -> AgentShapes.composerIdle
    }
    val shape by animateDpAsState(
        targetValue = targetShape,
        animationSpec = cornerRadiusSpec(),
        label = "composerShape",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        // Top corners are a real token — M3E's `extraLargeIncreased` — rather than
        // the `shape + 10.dp` fudge they used to be; only the bottom pair animates.
        shape = MaterialTheme.shapes.extraLargeIncreased.copy(
            bottomStart = CornerSize(shape),
            bottomEnd = CornerSize(shape),
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // No `tonalElevation`: the container role already raises this surface, and
        // stacking a 2dp tonal overlay on top of it double-counted the elevation.
    ) {
        Row(
            // The trailing control is a 48dp disc, and the composer's corners are cut
            // at 26dp, so the arc is 9.5dp deep at the disc's own height. With the
            // old 6dp end padding the disc's bottom edge (6dp up) sat inside that
            // arc, which is why it read as crammed into the corner; 10dp clears the
            // arc on both axes and lifts the disc's centre to the bar's centre.
            modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = {
                    Text(
                        when {
                            attachments.isNotEmpty() -> "Add a note (optional)…"
                            running -> "Steer the agent…"
                            else -> "Message your agent…"
                        },
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
                shape = MaterialTheme.shapes.medium,
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

            // The attach control sits between the text and the action disc rather
            // than on the other side of the field: the composer is a row of things
            // the user may want to do to *this* message, and putting the picker
            // next to send is what makes "add a file, then send" one motion.
            AttachButton(
                // Steering and stopping take no attachments — `session.steer` is
                // text-only — so offering the picker mid-turn would collect files
                // that the next tap silently discards.
                enabled = enabled && !running,
                onAttach = onAttach,
            )

            Spacer(Modifier.width(4.dp))

            AnimatedContent(
                targetState = when {
                    steering -> ComposerAction.STEER
                    running -> ComposerAction.STOP
                    else -> ComposerAction.SEND
                },
                transitionSpec = {
                    (scaleIn(animationSpec = morphSpec) togetherWith
                        scaleOut(animationSpec = morphSpec))
                },
                label = "composerAction",
            ) { action ->
                when (action) {
                    ComposerAction.SEND -> FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        // The default `IconButtonShapes` is round at rest (a 48dp disc)
                        // and squares off to the medium radius while held — so the
                        // send control keeps its shape and gains M3E's press morph
                        // instead of pinning `CircleShape`.
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Send message",
                        )
                    }

                    ComposerAction.STEER -> Button(
                        onClick = onSteer,
                        // A pill, not a 16dp rectangle: the three actions share this
                        // slot and morph into one another, so they have to stay in
                        // one shape family or the morph changes family mid-flight.
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.AltRoute, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Steer")
                    }

                    ComposerAction.STOP -> FilledTonalIconButton(
                        onClick = onStop,
                        shapes = IconButtonDefaults.shapes(),
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

/** Which picker the user meant by "attach". */
enum class AttachSource { PHOTO, AUDIO, DOCUMENT }

/**
 * The attach affordance: one control, three sources.
 *
 * A menu rather than three buttons, because two of the three sources are rare —
 * a voice note or a PDF — and giving them equal billing with the photo picker
 * would trade a couple of taps saved for a permanently wider composer. A plain
 * `IconButton` rather than a filled one keeps the send disc the only emphasised
 * control in the row, which is where the eye should end up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AttachButton(
    enabled: Boolean,
    onAttach: (AttachSource) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Attach a photo, sound or file")
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Photo") },
                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                onClick = {
                    open = false
                    onAttach(AttachSource.PHOTO)
                },
            )
            DropdownMenuItem(
                text = { Text("Audio") },
                leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                onClick = {
                    open = false
                    onAttach(AttachSource.AUDIO)
                },
            )
            DropdownMenuItem(
                text = { Text("File") },
                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                onClick = {
                    open = false
                    onAttach(AttachSource.DOCUMENT)
                },
            )
        }
    }
}

/**
 * The address media rows should fetch from, for whichever connection state the
 * app is in.
 *
 * Every non-idle state carries a profile, including a failed one: the transcript
 * on screen is still that server's, and its attachments should keep resolving
 * while a reconnect is in progress. Only [ConnectionStatus.Idle] — no profile
 * configured at all — has nothing to point at.
 */
private fun ConnectionStatus.baseUrlOrNull(): String? = when (this) {
    is ConnectionStatus.Connected -> profile.baseUrl
    is ConnectionStatus.Reconnecting -> profile.baseUrl
    is ConnectionStatus.Connecting -> profile.baseUrl
    is ConnectionStatus.Failed -> profile?.baseUrl
    ConnectionStatus.Idle -> null
}
