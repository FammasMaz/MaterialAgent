package com.materialagent.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewStream
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.BuildConfig
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.data.HapticLevel
import com.materialagent.data.MotionLevel
import com.materialagent.data.PaletteMode
import com.materialagent.data.ThemeMode
import com.materialagent.data.update.AppUpdate
import com.materialagent.data.update.UpdateState
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.components.ExpressiveToggleGroup
import com.materialagent.ui.components.AgentMark
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.SectionHeader
import com.materialagent.ui.components.scrollHaptics
import com.materialagent.ui.components.UpdateProgressBar
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.ExpressiveMotion
import com.materialagent.ui.theme.LocalScrollHaptics

/** Appearance, behaviour, server and about — the whole app's knobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: AgentViewModel,
    onConnect: () -> Unit,
) {
    val cue = rememberCue()
    val settings by app.settings.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    val status by app.status.collectAsStateWithLifecycle()
    val skin by app.skin.collectAsStateWithLifecycle()
    val serverVersion by app.serverVersion.collectAsStateWithLifecycle()
    val updateState by app.updateState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollHaptics(LocalScrollHaptics.current),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(modifier = Modifier.statusBarsPadding()) {
                Spacer(Modifier.height(12.dp))
                Text("Settings", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(12.dp))
                ConnectionCard(
                    status = status,
                    serverVersion = serverVersion,
                    onConnect = onConnect,
                    onDisconnect = {
                        cue(HapticCue.INTERRUPTED)
                        app.disconnect()
                    },
                    onRetry = app::retry,
                )
            }
        }

        // ── Appearance ──────────────────────────────────────────────────────
        item { SectionHeader("Appearance") }
        item {
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Rounded.Brightness6,
                    title = "Theme",
                    subtitle = "Follow the system, or pin light or dark",
                ) {
                    ChoiceRow(
                        options = listOf(
                            "System" to (settings.themeMode == ThemeMode.SYSTEM),
                            "Light" to (settings.themeMode == ThemeMode.LIGHT),
                            "Dark" to (settings.themeMode == ThemeMode.DARK),
                        ),
                        onSelect = { index ->
                            cue(HapticCue.SENT)
                            app.update {
                                it.copy(
                                    themeMode = when (index) {
                                        1 -> ThemeMode.LIGHT
                                        2 -> ThemeMode.DARK
                                        else -> ThemeMode.SYSTEM
                                    },
                                )
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = "Colour",
                    subtitle = if (skin != null) {
                        "Wallpaper colours, Hermes gold, or the server's skin"
                    } else {
                        "Wallpaper colours, or the Hermes palette"
                    },
                ) {
                    ChoiceRow(
                        options = listOf(
                            "Dynamic" to (settings.palette == PaletteMode.DYNAMIC),
                            "Hermes" to (settings.palette == PaletteMode.HERMES),
                            "Server" to (settings.palette == PaletteMode.HERMES_SKIN),
                        ),
                        onSelect = { index ->
                            cue(HapticCue.SENT)
                            app.update {
                                it.copy(
                                    palette = when (index) {
                                        1 -> PaletteMode.HERMES
                                        2 -> PaletteMode.HERMES_SKIN
                                        else -> PaletteMode.DYNAMIC
                                    },
                                )
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingsRow(
                    icon = Icons.Rounded.MotionPhotosOn,
                    title = "Motion",
                    subtitle = "Expressive springs, or calmer transitions",
                ) {
                    ChoiceRow(
                        options = listOf(
                            "Full" to (settings.motionLevel == MotionLevel.FULL),
                            "Reduced" to (settings.motionLevel == MotionLevel.REDUCED),
                        ),
                        onSelect = { index ->
                            cue(HapticCue.SENT)
                            app.update {
                                it.copy(
                                    motionLevel = if (index == 1) {
                                        MotionLevel.REDUCED
                                    } else {
                                        MotionLevel.FULL
                                    },
                                )
                            }
                        },
                    )
                }

                SettingsDivider()

                SettingsRow(
                    icon = Icons.Rounded.Vibration,
                    title = "Haptics",
                    subtitle = "How much the app touches back",
                ) {
                    ChoiceRow(
                        options = listOf(
                            "Off" to (settings.hapticLevel == HapticLevel.OFF),
                            "Subtle" to (settings.hapticLevel == HapticLevel.SUBTLE),
                            "Normal" to (settings.hapticLevel == HapticLevel.STANDARD),
                            "Strong" to (settings.hapticLevel == HapticLevel.STRONG),
                        ),
                        onSelect = { index ->
                            val level = HapticLevel.entries.getOrElse(index) { HapticLevel.STANDARD }
                            cue(HapticCue.TURN_COMPLETE)
                            app.update { it.copy(hapticLevel = level) }
                        },
                    )
                }
            }
        }

        // ── Conversation ────────────────────────────────────────────────────
        item { SectionHeader("Conversation") }
        item {
            SettingsGroup {
                SwitchRow(
                    icon = Icons.Rounded.Psychology,
                    title = "Show reasoning",
                    subtitle = "Display the agent's thinking above its answer",
                    checked = settings.showReasoning,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(showReasoning = value) }
                    },
                )
                SettingsDivider()
                SwitchRow(
                    icon = Icons.Rounded.Bolt,
                    title = "Show tool calls",
                    subtitle = "Every file read, command and search the agent runs",
                    checked = settings.showToolCalls,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(showToolCalls = value) }
                    },
                )
                SettingsDivider()
                SwitchRow(
                    icon = Icons.Rounded.Vibration,
                    title = "Streaming haptics",
                    subtitle = "A gentle tick while long answers stream in",
                    checked = settings.streamingHaptics,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(streamingHaptics = value) }
                    },
                )
                SettingsDivider()
                SwitchRow(
                    icon = Icons.Rounded.ArrowUpward,
                    title = "Scroll haptics",
                    subtitle = "A tick under your finger as lists move",
                    checked = settings.scrollHaptics,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(scrollHaptics = value) }
                    },
                )
                SettingsDivider()
                SwitchRow(
                    icon = Icons.Rounded.ViewStream,
                    title = "Group sessions",
                    subtitle = "Fold repeat runs of a cron job into one entry",
                    checked = settings.groupSessions,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(groupSessions = value) }
                    },
                )
                SettingsDivider()
                SwitchRow(
                    icon = Icons.Rounded.EditNote,
                    title = "Send on Enter",
                    subtitle = "Off means Enter adds a line and the button sends",
                    checked = settings.sendOnEnter,
                    onCheckedChange = { value ->
                        cue(HapticCue.SENT)
                        app.update { it.copy(sendOnEnter = value) }
                    },
                )
            }
        }

        // ── Servers ─────────────────────────────────────────────────────────
        item { SectionHeader("Servers") }
        item {
            SettingsGroup {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = Icons.Rounded.Hub,
                        title = profile.name,
                        subtitle = profile.baseUrl,
                        trailing = {
                            if ((status as? ConnectionStatus.Connected)?.profile?.id == profile.id) {
                                MetaPill(
                                    text = "Connected",
                                    container = MaterialTheme.colorScheme.primaryContainer,
                                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        },
                    )
                }
                if (profiles.isEmpty()) {
                    SettingsRow(
                        icon = Icons.Rounded.Hub,
                        title = "No servers saved",
                        subtitle = "Add one to start talking to your agent",
                    )
                }
                SettingsDivider()
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            cue(HapticCue.SENT)
                            onConnect()
                        },
                        shape = RoundedCornerShape(50),
                    ) { Text("Add or switch") }
                    if (status is ConnectionStatus.Connected) {
                        Button(
                            onClick = {
                                cue(HapticCue.INTERRUPTED)
                                app.disconnect()
                            },
                            shape = RoundedCornerShape(50),
                        ) { Text("Disconnect") }
                    }
                }
            }
        }

        // ── About ───────────────────────────────────────────────────────────
        item { SectionHeader("About") }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentMark(size = 52.dp, sheen = true, gradient = true)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MaterialAgent", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME} · agent $serverVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (skin != null) {
                            Spacer(Modifier.height(6.dp))
                            MetaPill(text = "Skin: ${skin?.name}", icon = Icons.Rounded.AutoAwesome)
                        }
                    }
                }
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.DarkMode,
                    title = "Material 3 Expressive",
                    subtitle = "Springy shape, colour and motion — the design language this " +
                        "app is built on",
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Link,
                    title = "Source",
                    subtitle = "github.com/FammasMaz/MaterialAgent",
                    trailing = {
                        Icon(
                            Icons.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        cue(HapticCue.SENT)
                        runCatching {
                            uriHandler.openUri("https://github.com/FammasMaz/MaterialAgent")
                        }
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = "Hermes gateway",
                    subtitle = "Requires `hermes serve` with the dashboard enabled",
                )
            }
        }

        // The updater sits with About: "which version am I on" and "is there a
        // newer one" are the same question. It stays one card, so the groups
        // around it keep the shape they already had.
        item {
            UpdateCard(
                state = updateState,
                currentVersion = BuildConfig.VERSION_NAME,
                autoCheck = settings.autoCheckUpdates,
                onAutoCheckChange = { value ->
                    cue(HapticCue.SENT)
                    app.update { it.copy(autoCheckUpdates = value) }
                },
                onCheck = {
                    cue(HapticCue.SENT)
                    app.checkForUpdates(force = true)
                },
                onDownload = { update -> app.downloadUpdate(update) },
                onInstall = { app.installUpdate() },
                onCancelDownload = { app.cancelUpdateDownload() },
                onSkipVersion = {
                    cue(HapticCue.TURN_COMPLETE)
                    app.skipUpdateVersion()
                },
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    status: ConnectionStatus,
    serverVersion: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = when (status) {
            is ConnectionStatus.Connected -> MaterialTheme.colorScheme.primaryContainer
            is ConnectionStatus.Failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = ExpressiveMotion.Specs.color,
        label = "connectionContainer",
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Hub, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (status) {
                            is ConnectionStatus.Connected -> status.profile.name
                            is ConnectionStatus.Connecting -> "Connecting to ${status.profile.name}"
                            is ConnectionStatus.Reconnecting ->
                                "Reconnecting to ${status.profile.name}"
                            is ConnectionStatus.Failed -> status.profile?.name ?: "Connection lost"
                            ConnectionStatus.Idle -> "Not connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when (status) {
                            is ConnectionStatus.Connected -> status.profile.baseUrl
                            is ConnectionStatus.Failed -> status.message
                            ConnectionStatus.Idle -> "Pick a server to begin"
                            else -> "Hold on…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                serverVersion?.let { MetaPill(text = "v$it") }
            }

            Row(
                modifier = Modifier.padding(start = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (status) {
                    is ConnectionStatus.Connected -> TextButton(onClick = onDisconnect) {
                        Text("Disconnect")
                    }

                    is ConnectionStatus.Failed -> TextButton(onClick = onRetry) { Text("Retry") }

                    ConnectionStatus.Idle -> Button(
                        onClick = onConnect,
                        shape = RoundedCornerShape(50),
                    ) { Text("Connect a server") }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsDivider() {
    // A Spacer with only a height paints nothing at all: this used to be a
    // 1dp `Spacer`, so every one of the ten separators in this screen was
    // invisible. It needs a divider that actually draws.
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

// The label column inside a settings row: a 20dp leading icon plus a 12dp gap.
// Controls that belong to a label are indented by this much so both share an edge.
private val RowLabelIndent = 32.dp

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickableRowCompat(onClick)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(modifier = modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke()
        }
        if (content != null) {
            Spacer(Modifier.height(10.dp))
            // Indented to the label column: 20dp icon + 12dp gap. Without this the
            // control starts at the card's content edge while its own label starts
            // 32dp further right, which reads as a misaligned button — the whole
            // point of the paired label and control is that they share a left edge.
            Column(modifier = Modifier.padding(start = RowLabelIndent)) { content() }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A compact expressive selector used for every enumerated preference. */
@Composable
private fun ChoiceRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    ExpressiveToggleGroup(
        options = options.map { it.first },
        selectedIndex = options.indexOfFirst { it.second }.coerceAtLeast(0),
        onSelect = onSelect,
        fillWidth = true,
    )
}

private fun Modifier.clickableRowCompat(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/**
 * The updater's home in Settings.
 *
 * It reuses the surrounding row vocabulary rather than inventing its own, so the
 * version you are on and the version you could be on read as one answer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateCard(
    state: UpdateState,
    currentVersion: String,
    autoCheck: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onDownload: (AppUpdate) -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkipVersion: () -> Unit,
) {
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    SettingsGroup {
        SettingsRow(
            icon = Icons.Rounded.SystemUpdate,
            title = "Updates",
            subtitle = "Version $currentVersion · ${updateStatus(state)}",
            trailing = {
                Switch(
                    checked = autoCheck,
                    onCheckedChange = onAutoCheckChange,
                    enabled = BuildConfig.EXTERNAL_UPDATES_ENABLED,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is UpdateState.Downloading -> UpdateProgressBar(
                        progress = state.progress,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )

                    is UpdateState.Checking -> LoadingIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )

                    is UpdateState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onCheck,
                        enabled = BuildConfig.EXTERNAL_UPDATES_ENABLED && !busy,
                    ) { Text("Check for updates") }

                    Spacer(Modifier.weight(1f))

                    when (state) {
                        is UpdateState.Available -> Button(
                            onClick = { onDownload(state.update) },
                            shape = RoundedCornerShape(50),
                        ) { Text("Update") }

                        is UpdateState.Downloading -> TextButton(onClick = onCancelDownload) {
                            Text("Cancel")
                        }

                        is UpdateState.ReadyToInstall -> Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(50),
                        ) { Text("Install") }

                        else -> Unit
                    }
                }

                if (state is UpdateState.Available) {
                    TextButton(onClick = onSkipVersion) { Text("Skip this version") }
                }
            }
        }
    }
}

/** The card subtitle: what the updater is doing right now, in one line. */
private fun updateStatus(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Tap to check for a newer build"
    UpdateState.Checking -> "Checking GitHub…"
    UpdateState.UpToDate -> "Up to date"
    is UpdateState.Available -> "Version ${state.update.versionName} is available"
    is UpdateState.Downloading -> if (state.progress >= 0f) {
        "Downloading ${(state.progress * 100).toInt()}%"
    } else {
        "Downloading…"
    }
    is UpdateState.ReadyToInstall -> "Version ${state.update.versionName} ready to install"
    is UpdateState.Failed -> "Last check did not complete"
}
