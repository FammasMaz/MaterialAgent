package com.materialagent.ui.screens.sessions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.core.model.SessionSummary
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.containerViewModel
import com.materialagent.ui.components.AgentArt
import com.materialagent.ui.components.EmptyState
import com.materialagent.ui.components.ErrorBanner
import com.materialagent.ui.components.LoadingBlock
import com.materialagent.ui.components.LivePulse
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.scrollHaptics
import com.materialagent.ui.components.ExpressiveToggleGroup
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.AgentShapes
import com.materialagent.ui.theme.LocalScrollHaptics
import com.materialagent.ui.theme.alphaSpec
import com.materialagent.ui.theme.colorSpec
import com.materialagent.ui.theme.placementSpec
import com.materialagent.ui.theme.scaleSpec
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The home screen: every durable conversation the server knows about.
 *
 * The list is a pure function of the server's catalogue plus a local query, so it
 * stays correct when the agent renames a conversation mid-turn — the refresh is
 * driven by the gateway's `sessions.changed` events, not by the user pulling.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionsScreen(
    app: AgentViewModel,
    onOpenSession: (String) -> Unit,
    onNewConversation: () -> Unit,
    onConnect: () -> Unit,
) {
    val viewModel = containerViewModel { SessionsViewModel(it) }
    val cue = rememberCue()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val collapsedGroups by viewModel.collapsedGroups.collectAsStateWithLifecycle()
    val status by app.status.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // One row's wiring, shared by the flat and grouped lists so the two paths
    // cannot drift apart.
    val sessionRow: @Composable (SessionSummary) -> Unit = { session ->
        SessionCard(
            session = session,
            busy = busyId == session.id,
            onOpen = {
                cue(HapticCue.UI_ACTION)
                onOpenSession(session.id)
            },
            onRename = {
                cue(HapticCue.UI_ACTION)
                renaming = session
            },
            onDelete = {
                cue(HapticCue.NEEDS_ATTENTION)
                deleting = session
            },
            onBranch = {
                cue(HapticCue.UI_ACTION)
                viewModel.branch(session.id) { newId ->
                    onOpenSession(newId)
                }
            },
        )
    }

    LaunchedEffect(loading) {
        if (refreshing && !loading) refreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                cue(HapticCue.REFRESH)
                viewModel.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollHaptics(LocalScrollHaptics.current),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    // Clear the floating navigation bar.
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Spacer(Modifier.height(12.dp))
                        HeaderRow(
                            connectedName = (status as? ConnectionStatus.Connected)?.profile?.name,
                            status = status,
                            onConnect = onConnect,
                            onRetry = app::retry,
                        )
                        Spacer(Modifier.height(14.dp))
                        SearchField(
                            query = query,
                            onQueryChange = viewModel::search,
                            onClear = viewModel::clearSearch,
                        )
                        Spacer(Modifier.height(10.dp))
                        ExpressiveToggleGroup(
                            options = SessionFilter.entries.map { it.label },
                            selectedIndex = SessionFilter.entries.indexOf(filter),
                            onSelect = { index ->
                                cue(HapticCue.TOGGLE)
                                viewModel.filterBy(SessionFilter.entries[index])
                            },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // A list error is only meaningful once we have a connection; when the
                // gateway is down the empty state already says what to do about it.
                if (error != null && status is ConnectionStatus.Connected) {
                    item {
                        ErrorBanner(
                            message = error!!,
                            onDismiss = { viewModel.dismissError() },
                            onRetry = viewModel::refresh,
                        )
                    }
                }
                if (actionError != null) {
                    item {
                        ErrorBanner(
                            message = actionError!!,
                            onDismiss = { viewModel.dismissError() },
                        )
                    }
                }

                when {
                    loading && sessions.isEmpty() -> item {
                        LoadingBlock("Reading the server's conversations…")
                    }

                    sessions.isEmpty() && query.isBlank() -> item {
                        EmptyState(
                            title = if (filter == SessionFilter.AUTOMATIONS) {
                                "No automations yet"
                            } else {
                                "Nothing here yet"
                            },
                            body = if (status is ConnectionStatus.Connected) {
                                "Start a conversation and your agent will pick up from a " +
                                    "clean slate — your workspace, its tools, no history."
                            } else {
                                "Connect to your Hermes server to see conversations and talk " +
                                    "to your agent."
                            },
                            actionLabel = "New conversation",
                            onAction = {
                                cue(HapticCue.UI_ACTION)
                                onNewConversation()
                            },
                            secondaryActionLabel = "Server settings",
                            onSecondaryAction = onConnect,
                        )
                    }

                    sessions.isEmpty() -> item {
                        EmptyState(
                            title = "No matches",
                            body = "No conversation matches “$query”.",
                            actionLabel = "Clear search",
                            onAction = viewModel::clearSearch,
                        )
                    }

                    // Grouped mode folds the same rows under a header per group;
                    // the list itself is unchanged when the preference is off.
                    else -> if (settings.groupSessions) {
                        val rows = groupSessions(sessions, collapsedGroups)
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is SessionListItem.GroupHeader -> SessionGroupHeaderRow(
                                    header = row,
                                    onToggle = {
                                        cue(HapticCue.TOGGLE)
                                        viewModel.toggleGroup(row.groupKey)
                                    },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = alphaSpec(),
                                        placementSpec = placementSpec(),
                                        fadeOutSpec = null,
                                    ),
                                )

                                is SessionListItem.Row -> Box(
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = alphaSpec(),
                                        placementSpec = placementSpec(),
                                        fadeOutSpec = null,
                                    ),
                                ) {
                                    sessionRow(row.session)
                                }
                            }
                        }
                    } else {
                        items(sessions, key = { it.id }) { session ->
                            sessionRow(session)
                        }
                    }
                }
            }
        }
    }

    renaming?.let { session ->
        RenameDialog(
            initial = session.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                viewModel.rename(session.id, title)
                renaming = null
            },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this conversation?") },
            text = {
                Text(
                    "“${session.title}” and its ${session.messageCount} messages will be " +
                        "removed from the server. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cue(HapticCue.DESTRUCTIVE)
                        viewModel.delete(session.id)
                        deleting = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, shapes = ButtonDefaults.shapes()) {
                    Text("Keep")
                }
            },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeaderRow(
    connectedName: String?,
    status: ConnectionStatus,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sessions",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (status) {
                        is ConnectionStatus.Connected -> {
                            LivePulse()
                            Text(
                                text = connectedName ?: "Connected",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is ConnectionStatus.Connecting -> Text(
                            text = "Connecting to ${status.profile.name}…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        is ConnectionStatus.Reconnecting -> Text(
                            text = "Reconnecting (attempt ${status.attempt})…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        is ConnectionStatus.Failed -> {
                            Text(
                                text = status.message,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        ConnectionStatus.Idle -> {
                            TextButton(onClick = onConnect, shapes = ButtonDefaults.shapes()) {
                                Text("Connect a server")
                            }
                        }
                    }
                }
            }
            if (status is ConnectionStatus.Failed) {
                TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("Retry") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search conversations") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = AgentShapes.pill,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionCard(
    session: SessionSummary,
    busy: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dim by animateFloatAsState(
        targetValue = if (busy) 0.5f else 1f,
        animationSpec = alphaSpec(),
        label = "busyDim",
    )
    val container by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = colorSpec(),
        label = "cardContainer",
    )

    Surface(
        onClick = onOpen,
        enabled = !busy,
        // A step of the app's scale rather than a one-off 24dp: it sits beside
        // the settings cards, and two list screens' near-identical containers
        // differing by 2dp reads as an accident.
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(dim),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (session.isAutomation) {
                Surface(
                    shape = AgentShapes.pill,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                AgentArt(size = 38.dp)
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = session.preview.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaPill(text = relativeTime(session.startedAt))
                    if (session.messageCount > 0) {
                        MetaPill(text = "${session.messageCount} messages")
                    }
                    if (session.isAutomation) MetaPill(text = "Automation")
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Conversation actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Branch from here") },
                        leadingIcon = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onBranch()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * A collapsible header for one group of sessions.
 *
 * The chevron is a single arrow that rotates rather than two icons that swap,
 * so expanding and collapsing read as the same object turning over.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionGroupHeaderRow(
    header: SessionListItem.GroupHeader,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (header.collapsed) -90f else 0f,
        animationSpec = scaleSpec(),
        label = "groupChevron",
    )
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (header.isCron) {
                Surface(
                    shape = AgentShapes.pill,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = header.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            MetaPill(text = runCountLabel(header.runCount, header.isCron))
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (header.collapsed) {
                    "Expand ${header.label}"
                } else {
                    "Collapse ${header.label}"
                },
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation),
            )
        }
    }
}

/** "3 runs" for an automation, "3 sessions" for a source group. */
private fun runCountLabel(count: Int, isCron: Boolean): String = if (isCron) {
    "$count run${if (count == 1) "" else "s"}"
} else {
    "$count session${if (count == 1) "" else "s"}"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Title") },
                singleLine = true,
                // Every other field is `medium` or the pill; the dialog's own
                // 32dp is the *container's* radius, not the field's.
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        },
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
    )
}

/** Human-friendly age. Minutes and hours matter far more than exact stamps here. */
internal fun relativeTime(epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return "No date"
    val millis = (epochSeconds * 1000).toLong()
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        delta < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(delta)}m ago"
        delta < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(delta)}h ago"
        delta < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(delta)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
