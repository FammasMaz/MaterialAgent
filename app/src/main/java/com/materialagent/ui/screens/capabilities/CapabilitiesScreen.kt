package com.materialagent.ui.screens.capabilities

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.core.model.ProviderInfo
import com.materialagent.core.model.ToolsetInfo
import com.materialagent.core.str
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.components.ExpressiveToggleGroup
import com.materialagent.ui.components.EmptyState
import com.materialagent.ui.components.ErrorBanner
import com.materialagent.ui.components.LoadingBlock
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.SectionHeader
import com.materialagent.ui.containerViewModel
import com.materialagent.ui.rememberCue
import kotlinx.serialization.json.JsonObject

/** What the agent on the other end can do — read-only, straight from the server. */
private enum class CapabilityTab(val label: String) {
    MODELS("Models"),
    TOOLS("Tools"),
    SKILLS("Skills"),
    MCP("MCP"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(
    app: AgentViewModel,
    onConnect: () -> Unit,
) {
    val viewModel = containerViewModel { CapabilitiesViewModel(it) }
    val cue = rememberCue()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val toolsets by viewModel.toolsets.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val mcpServers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val connection by app.status.collectAsStateWithLifecycle()
    val usage by app.chat.transcript.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(CapabilityTab.MODELS) }
    val connected = connection is ConnectionStatus.Connected
    val usageSnapshot = usage.info?.usage

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(modifier = Modifier.statusBarsPadding()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Agent", style = MaterialTheme.typography.displaySmall)
                        Text(
                            text = when (val current = connection) {
                                is ConnectionStatus.Connected ->
                                    "${current.profile.name} · ${providers.sumOf { it.models.size }} models · " +
                                        "${toolsets.size} toolsets"
                                is ConnectionStatus.Connecting -> "Connecting…"
                                is ConnectionStatus.Reconnecting -> "Reconnecting…"
                                is ConnectionStatus.Failed -> current.message
                                ConnectionStatus.Idle -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            cue(HapticCue.SENT)
                            viewModel.refresh()
                        },
                        enabled = connected && !refreshing,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh capabilities")
                    }
                }
                Spacer(Modifier.height(12.dp))
                ExpressiveToggleGroup(
                    options = CapabilityTab.entries.map { it.label },
                    selectedIndex = CapabilityTab.entries.indexOf(tab),
                    onSelect = { index ->
                        cue(HapticCue.SENT)
                        tab = CapabilityTab.entries[index]
                    },
                    fillWidth = true,
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        if (!connected) {
            item {
                EmptyState(
                    title = "Not connected",
                    body = "Capabilities live on the server. Connect to see which models, " +
                        "tools, skills and MCP servers your agent has.",
                    actionLabel = "Connect a server",
                    onAction = onConnect,
                )
            }
        }

        // Capability errors are only worth surfacing while we believe we are
        // connected; otherwise the "not connected" state is the real story.
        if (connected) {
            error?.let { message ->
                item {
                    ErrorBanner(
                        message = message,
                        onDismiss = viewModel::dismissError,
                        onRetry = viewModel::refresh,
                    )
                }
            }
        }
        actionError?.let { message ->
            item { ErrorBanner(message = message, onDismiss = viewModel::dismissError) }
        }

        if (connected) {
            if (refreshing && providers.isEmpty() && toolsets.isEmpty()) {
                item { LoadingBlock("Asking the server what it can do…") }
            }

            usageSnapshot?.let { snapshot ->
                item(key = "usage") { UsageCard(snapshot) }
            }

            when (tab) {
                CapabilityTab.MODELS -> {
                    if (providers.isEmpty() && !refreshing) {
                        item {
                            EmptyState(
                                title = "No models reported",
                                body = "The server is connected but has not told us about any " +
                                    "providers yet.",
                                actionLabel = "Ask again",
                                onAction = viewModel::refresh,
                            )
                        }
                    }
                    items(providers, key = { it.slug }) { provider ->
                        ProviderCard(provider)
                    }
                }

                CapabilityTab.TOOLS -> {
                    if (toolsets.isEmpty() && !refreshing) {
                        item {
                            EmptyState(
                                title = "No toolsets",
                                body = "This server is running without any tool servers enabled.",
                            )
                        }
                    }
                    items(toolsets, key = { it.name }) { toolset ->
                        ToolsetCard(
                            toolset = toolset,
                            onChange = { enabled ->
                                cue(HapticCue.TOOL_DONE)
                                viewModel.setToolsetEnabled(toolset.name, enabled)
                            },
                        )
                    }
                }

                CapabilityTab.SKILLS -> {
                    if (skills.isEmpty() && !refreshing) {
                        item {
                            EmptyState(
                                title = "No skills",
                                body = "Skills are the agent's learned playbooks. This server " +
                                    "does not have any installed.",
                            )
                        }
                    }
                    skills.forEach { (category, names) ->
                        item(key = "skill-$category") {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.School,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = category,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        MetaPill(text = "${names.size}")
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    names.forEach { name ->
                                        Text(
                                            text = "• $name",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                CapabilityTab.MCP -> {
                    if (mcpServers.isEmpty() && !refreshing) {
                        item {
                            EmptyState(
                                title = "No MCP servers",
                                body = "Model Context Protocol servers extend the agent with " +
                                    "outside services. This server has none attached.",
                            )
                        }
                    }
                    items(mcpServers.size) { index ->
                        McpCard(mcpServers[index])
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageCard(usage: com.materialagent.core.model.Usage) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Context window", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                MetaPill(text = "${usage.contextPercent}%")
            }
            LinearProgressIndicator(
                progress = { usage.contextFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaPill(text = "${usage.contextUsed} / ${usage.contextMax}")
                MetaPill(text = "${usage.calls} calls")
                usage.avgTps?.let { MetaPill(text = String.format("%.1f tok/s", it)) }
                if (usage.compressions > 0) MetaPill(text = "${usage.compressions} compactions")
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: ProviderInfo) {
    var expanded by remember { mutableStateOf(provider.isCurrent) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (provider.isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    provider.authType?.let { auth ->
                        Text(
                            text = auth,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MetaPill(text = "${provider.models.size}")
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (!provider.authenticated) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = provider.warning ?: "This provider is not signed in.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    provider.models.forEach { model ->
                        Text(
                            text = "• $model",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsetCard(
    toolset: ToolsetInfo,
    onChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(toolset.name, style = MaterialTheme.typography.titleMedium)
                    if (toolset.description.isNotBlank()) {
                        Text(
                            text = toolset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(checked = toolset.enabled, onCheckedChange = onChange)
            }
            if (toolset.tools.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                ) {
                    MetaPill(text = "${toolset.toolCount} tools")
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        toolset.tools.forEach { tool ->
                            Text(
                                text = "• $tool",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** MCP servers arrive as loose JSON — render whatever fields exist, never assume. */
@Composable
private fun McpCard(server: JsonObject) {
    val name = server.str("name")
        ?: server.str("id")
        ?: server.str("command")
        ?: "MCP server"
    val status = server.str("status") ?: server.str("state")
    val transport = server.str("transport") ?: server.str("type")
    val toolCount = server.entries
        .firstOrNull { it.key == "tools" }
        ?.value
        ?.let { value ->
            (value as? kotlinx.serialization.json.JsonArray)?.size
        }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                val subtitle = listOfNotNull(transport, status).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (toolCount != null) MetaPill(text = "$toolCount tools")
        }
    }
}
