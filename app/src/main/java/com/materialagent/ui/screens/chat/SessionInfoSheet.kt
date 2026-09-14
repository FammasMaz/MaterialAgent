package com.materialagent.ui.screens.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import com.materialagent.core.model.SessionSummary
import com.materialagent.core.model.Usage
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.ChatTranscript
import com.materialagent.ui.components.SectionHeader
import com.materialagent.ui.screens.sessions.relativeTime
import com.materialagent.ui.theme.CodeTextStyle
import com.materialagent.ui.theme.colorSpec
import com.materialagent.ui.theme.placementSpec
import kotlin.math.roundToInt

/*
 * What the app actually knows about the open conversation.
 *
 * Every value here is read from something the gateway really sends — the
 * `session.info` payload ([com.materialagent.core.model.SessionInfo]), the
 * `session.list` summary the inbox already caches, or the transcript's own ids.
 * Nothing is inferred or filled in with a plausible-looking default: a row the
 * server did not report is simply absent, and the surface says so where that is
 * surprising. Two fields the protocol documents but this build does not read
 * (`system_prompt`, `desktop_contract`) are deliberately left out — the first is
 * a wall of text nobody reads on a phone, the second is a client-internal
 * contract number.
 */

/** The context window as the server last reported it. */
internal data class ContextWindow(
    val used: Long,
    val max: Long,
    val fraction: Float,
    val percent: Int,
)

/**
 * Context usage from the last `session.info` / `message.complete`.
 *
 * Returns null when the server has not reported a window at all. That happens on
 * a session that is open but has not finished a turn: `session.resume` returns a
 * sparse `info` with no usage block, and the rich one only arrives with the next
 * event. A zero-length window is treated as "not reported" rather than as "0% of
 * 0", which would draw an empty bar that looks like a measurement.
 */
internal fun contextWindow(usage: Usage?): ContextWindow? {
    if (usage == null || usage.contextMax <= 0L) return null
    val used = usage.contextUsed.coerceAtLeast(0L)
    val fraction = (used.toDouble() / usage.contextMax.toDouble()).coerceIn(0.0, 1.0).toFloat()
    return ContextWindow(
        used = used,
        max = usage.contextMax,
        fraction = fraction,
        percent = (fraction * 100f).roundToInt(),
    )
}

/** Token counts the way a person reads them: `845`, `12.3k`, `1.2M`. */
internal fun formatTokens(count: Long): String = when {
    count < 0 -> "0"
    count < 1_000 -> count.toString()
    count < 1_000_000 -> trimTrailingZero(count / 1_000.0) + "k"
    else -> trimTrailingZero(count / 1_000_000.0) + "M"
}

/** One decimal place, but never a bare `12.0` — `12k` reads better than `12.0k`. */
private fun trimTrailingZero(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

/** A percentage the server supplied, shown only when it is a real number. */
private fun Long.asTokens(): String = formatTokens(this)

/**
 * Everything the info sheet shows, in one place.
 *
 * The sheet and its "copy all" text both read this, so a field can never appear
 * in one and not the other. [from] is pure — it takes the transcript and the
 * matching `session.list` row and does no I/O — which is what makes the display
 * testable off-device.
 */
internal data class SessionFacts(
    val runtimeId: String?,
    val storedId: String?,
    val title: String,
    val model: String?,
    val provider: String?,
    val reasoningEffort: String?,
    val serviceTier: String?,
    /** Null when the server never mentioned a fast tier, so "Off" is never claimed. */
    val fast: Boolean?,
    val yolo: Boolean,
    val approvalMode: String?,
    val personality: String?,
    val profile: String?,
    val project: String?,
    val cwd: String?,
    val branch: String?,
    val terminalBackend: String?,
    val source: String?,
    val messageCount: Int?,
    val startedAt: Double?,
    /** Null until `session.info` reports a catalogue — an empty one is a real zero. */
    val toolCount: Int?,
    val skillCount: Int?,
    val mcpServerCount: Int?,
    val version: String?,
) {
    companion object {
        fun from(transcript: ChatTranscript, summary: SessionSummary?): SessionFacts {
            val info = transcript.info
            return SessionFacts(
                runtimeId = transcript.sessionId,
                storedId = transcript.storedSessionId,
                title = transcript.title,
                model = info?.model,
                provider = info?.provider,
                reasoningEffort = info?.reasoningEffort,
                serviceTier = info?.serviceTier,
                fast = info?.fast,
                yolo = info?.yolo == true,
                approvalMode = info?.approvalMode,
                personality = info?.personality,
                profile = info?.profileName,
                project = info?.project,
                cwd = info?.cwd,
                branch = info?.branch,
                terminalBackend = info?.terminalBackend,
                source = summary?.source,
                messageCount = summary?.messageCount,
                startedAt = summary?.startedAt?.takeIf { it > 0.0 },
                toolCount = info?.toolCount,
                skillCount = info?.skillCount,
                mcpServerCount = info?.mcpServers?.size,
                version = info?.version,
            )
        }
    }
}

/**
 * True when there is at least one session row to draw.
 *
 * The heading is part of the group, so it goes when the group does: a heading
 * with nothing under it is the same empty container the reveal is meant not to
 * have. A brand new conversation has no ids yet — the session does not exist
 * until the first turn — so this is the common case, not an edge one.
 */
internal fun SessionFacts.hasSessionRows(): Boolean =
    !runtimeId.isNullOrBlank() || !storedId.isNullOrBlank() || !source.isNullOrBlank() ||
        messageCount != null || startedAt != null || !profile.isNullOrBlank() ||
        !project.isNullOrBlank() || !cwd.isNullOrBlank() || !branch.isNullOrBlank() ||
        !terminalBackend.isNullOrBlank()

/**
 * The whole card as plain text, for the copy button.
 *
 * Blank values are skipped rather than printed as "unknown": a pasted report is
 * read by a human or a model, and a line saying nothing is worse than no line.
 * Timestamps are left out on purpose — they are relative on screen and would
 * make this function depend on the clock, which is exactly what its test cannot do.
 */
internal fun SessionFacts.toReport(): String = buildList {
    add("MaterialAgent conversation")
    title.takeIf { it.isNotBlank() }?.let { add("Title: $it") }
    runtimeId?.takeIf { it.isNotBlank() }?.let { add("Runtime ID: $it") }
    storedId?.takeIf { it.isNotBlank() }?.let { add("Stored ID: $it") }
    source?.takeIf { it.isNotBlank() }?.let { add("Source: $it") }
    messageCount?.let { add("Messages: $it") }
    model?.takeIf { it.isNotBlank() }?.let { add("Model: $it") }
    provider?.takeIf { it.isNotBlank() }?.let { add("Provider: $it") }
    reasoningEffort?.takeIf { it.isNotBlank() }?.let { add("Reasoning: $it") }
    serviceTier?.takeIf { it.isNotBlank() }?.let { add("Service tier: $it") }
    if (fast != null) add("Fast tier: ${if (fast) "on" else "off"}")
    if (yolo) add("YOLO mode: on")
    approvalMode?.takeIf { it.isNotBlank() }?.let { add("Approvals: $it") }
    personality?.takeIf { it.isNotBlank() }?.let { add("Personality: $it") }
    profile?.takeIf { it.isNotBlank() }?.let { add("Profile: $it") }
    project?.takeIf { it.isNotBlank() }?.let { add("Project: $it") }
    cwd?.takeIf { it.isNotBlank() }?.let { add("Working directory: $it") }
    branch?.takeIf { it.isNotBlank() }?.let { add("Branch: $it") }
    terminalBackend?.takeIf { it.isNotBlank() }?.let { add("Terminal: $it") }
    toolCount?.let { add("Tools: $it") }
    skillCount?.let { add("Skills: $it") }
    mcpServerCount?.let { add("MCP servers: $it") }
    version?.takeIf { it.isNotBlank() }?.let { add("Server version: $it") }
}.joinToString("\n")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionInfoSheet(
    transcript: ChatTranscript,
    summary: SessionSummary?,
    onCue: (HapticCue) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Conversation info",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            SessionInfoBody(
                transcript = transcript,
                summary = summary,
                onCue = onCue,
            )
        }
    }
}

/**
 * Everything the app knows about the open conversation, in one body.
 *
 * The sheet and the transcript's pull-to-reveal header both render this, so a
 * field can never appear on one surface and be missing from the other, and the
 * wording can never drift between them. It is deliberately chrome-free — no
 * title, no sheet, no card, no scroll container — because the two surfaces are
 * only allowed to differ in their chrome.
 *
 * A row is drawn only when the server reported it. Hermes leaves most of this
 * out until a turn has completed, and an absent field is better left unsaid than
 * filled with a plausible-looking default: "Fast tier: off" on a session that
 * never mentioned a fast tier is a claim the app cannot make. Nothing here
 * explains the protocol, either; what a user cannot see is not their problem to
 * read about.
 *
 * The field mapping lives in [SessionFacts.from], which is pure and tested
 * off-device; this function only arranges it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SessionInfoBody(
    transcript: ChatTranscript,
    summary: SessionSummary?,
    onCue: (HapticCue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val facts = remember(transcript, summary) { SessionFacts.from(transcript, summary) }
    val window = remember(transcript.usage) { contextWindow(transcript.usage) }
    val usage = transcript.usage
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ContextBlock(window = window, usage = usage)

        // No heading over the model rows: they label themselves, and a "Model"
        // heading above a "Model" row says the same word twice.
        facts.model?.takeIf { it.isNotBlank() }?.let { MetaRow("Model", it, monospace = true) }
        facts.provider?.takeIf { it.isNotBlank() }?.let { MetaRow("Provider", it) }
        facts.reasoningEffort?.takeIf { it.isNotBlank() }?.let { MetaRow("Reasoning effort", it) }
        facts.serviceTier?.takeIf { it.isNotBlank() }?.let { MetaRow("Service tier", it) }
        facts.fast?.let { MetaRow("Fast tier", if (it) "On" else "Off") }
        facts.approvalMode?.takeIf { it.isNotBlank() }?.let { MetaRow("Approvals", it) }
        facts.personality?.takeIf { it.isNotBlank() }?.let { MetaRow("Personality", it) }
        if (facts.yolo) MetaRow("YOLO mode", "On — approvals are skipped")

        if (facts.hasSessionRows()) {
            SectionHeader("Session")
            CopyRow("Runtime ID", facts.runtimeId, onCue, "Runtime session id")
            CopyRow("Stored ID", facts.storedId, onCue, "Stored session id")
            facts.source?.takeIf { it.isNotBlank() }?.let {
                MetaRow("Started from", summary?.groupLabel ?: it)
            }
            facts.messageCount?.let { MetaRow("Messages", it.toString()) }
            facts.startedAt?.let { MetaRow("Started", relativeTime(it)) }
            facts.profile?.takeIf { it.isNotBlank() }?.let { MetaRow("Profile", it) }
            facts.project?.takeIf { it.isNotBlank() }?.let { MetaRow("Project", it) }
            CopyRow("Working directory", facts.cwd, onCue, "Working directory", monospace = true)
            facts.branch?.takeIf { it.isNotBlank() }?.let { MetaRow("Branch", it, monospace = true) }
            facts.terminalBackend?.takeIf { it.isNotBlank() }?.let { MetaRow("Terminal", it) }
        }

        val tools = facts.toolCount
        val skills = facts.skillCount
        val mcpServers = facts.mcpServerCount
        if (tools != null || skills != null || mcpServers != null) {
            SectionHeader("Capabilities")
            tools?.let { MetaRow("Tools", it.toString()) }
            skills?.let { MetaRow("Skills", it.toString()) }
            mcpServers?.let { MetaRow("MCP servers", it.toString()) }
        }

        facts.version?.takeIf { it.isNotBlank() }?.let {
            SectionHeader("Server")
            MetaRow("Hermes version", it)
        }

        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                val manager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                manager.setPrimaryClip(ClipData.newPlainText("Conversation info", facts.toReport()))
                onCue(HapticCue.UI_ACTION)
            },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Copy all details")
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Context usage, when there is a window to draw.
 *
 * A row and a bar rather than a card of its own. This sits on the same surface as
 * the transcript, and a box inside the reveal would read as a second panel
 * opening inside the first — the thing the reveal is meant not to be.
 *
 * When no window has been reported, the row says so in three words — or, if
 * nothing has come back from a turn at all, says when to expect it. Either way
 * it stays a row: a paragraph explaining which call delivers the window would be
 * describing the protocol to someone who only wanted to know how full the
 * context is, and a box around it was a panel inside the panel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContextBlock(window: ContextWindow?, usage: Usage?) {
    when {
        window != null -> Column(
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaRow(
                label = "Context",
                value = "${window.used.asTokens()} of ${window.max.asTokens()} · " +
                    "${window.percent}% used",
            )
            // A spatial change, so it animates on the placement spec: the bar may
            // settle into place rather than snap.
            val animated by animateFloatAsState(
                targetValue = window.fraction,
                animationSpec = placementSpec(),
                label = "contextFraction",
            )
            LinearWavyProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth(),
            )
            usage?.let { TokenBreakdown(it) }
        }

        // A window can be missing while the token totals are not: a resumed
        // session reports its ledger but no limit.
        usage != null && usage.hasReportedNumbers() -> {
            SectionHeader("Usage")
            TokenBreakdown(usage)
        }

        // No usage block has been seen at all, which on this gateway is what a
        // session that has not finished a turn looks like.
        usage == null -> MetaRow("Context", "Appears after the first turn")

        else -> MetaRow("Context", "Not reported")
    }
}

/**
 * True when the server reported at least one usage figure worth a row.
 *
 * [TokenBreakdown] draws only the numbers that are not zero and only the rates
 * it was given, so this mirrors it exactly — without it, a session whose usage
 * block arrived empty would get a "Usage" heading and nothing under it.
 */
internal fun Usage.hasReportedNumbers(): Boolean =
    input > 0 || output > 0 || reasoning > 0 || total > 0 || calls > 0 ||
        compressions > 0 || activeSubagents > 0 || cacheHitPercent != null ||
        (avgTps != null && avgTps > 0.0) || (avgLatencyS != null && avgLatencyS > 0.0)

/**
 * The token ledger.
 *
 * Every row is conditional: a zero is not a fact worth the space, and printing
 * it would make an unknown indistinguishable from a measurement.
 */
@Composable
private fun TokenBreakdown(usage: Usage) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (usage.input > 0) MetaRow("Input tokens", usage.input.asTokens())
        if (usage.output > 0) MetaRow("Output tokens", usage.output.asTokens())
        if (usage.reasoning > 0) MetaRow("Reasoning tokens", usage.reasoning.asTokens())
        if (usage.total > 0) MetaRow("Total tokens", usage.total.asTokens())
        usage.cacheHitPercent?.let { MetaRow("Cache hits", "$it%") }
        usage.avgTps?.takeIf { it > 0.0 }?.let { MetaRow("Average speed", "${trimTo2(it)} tok/s") }
        usage.avgLatencyS?.takeIf { it > 0.0 }?.let { MetaRow("Average latency", "${trimTo2(it)} s") }
        if (usage.calls > 0) MetaRow("Model calls", usage.calls.toString())
        if (usage.compressions > 0) MetaRow("Compressions", usage.compressions.toString())
        if (usage.activeSubagents > 0) MetaRow("Sub-agents running", usage.activeSubagents.toString())
    }
}

private fun trimTo2(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/** Label on the left, value wrapping under it on the right. */
@Composable
private fun MetaRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            text = value,
            style = if (monospace) CodeTextStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A [MetaRow] whose value is an identifier worth copying.
 *
 * The value wraps rather than ellipsizes: a truncated id is useless either to
 * read or to check, and this sheet exists precisely so the id can be checked.
 */
@Composable
private fun CopyRow(
    label: String,
    value: String?,
    onCue: (HapticCue) -> Unit,
    contentDescription: String,
    monospace: Boolean = true,
) {
    val text = value?.takeIf { it.isNotBlank() } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            text = text,
            style = if (monospace) CodeTextStyle else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        CopyButton(text = text, description = contentDescription, onCue = onCue)
    }
}

/** Copy affordance that confirms itself in place. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyButton(
    text: String,
    description: String,
    onCue: (HapticCue) -> Unit,
) {
    var copied by remember(text) { mutableStateOf(false) }
    val context = LocalContext.current
    // An effects change — colour only — so it takes the effects spec and can
    // never overshoot into a flash.
    val tint by animateColorAsState(
        targetValue = if (copied) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = colorSpec(),
        label = "copyTint",
    )
    IconButton(
        onClick = {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText(description, text))
            copied = true
            onCue(HapticCue.UI_ACTION)
        },
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Done else Icons.Rounded.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy $description",
            tint = tint,
        )
    }
}

