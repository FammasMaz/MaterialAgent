package com.materialagent.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialagent.data.HapticCue
import com.materialagent.data.chat.EntryKind
import com.materialagent.data.chat.EntryStatus
import com.materialagent.data.chat.InteractiveRequest
import com.materialagent.data.chat.TodoItem
import com.materialagent.data.chat.ToolInfo
import com.materialagent.data.chat.TranscriptEntry
import com.materialagent.ui.components.AgentOrb
import com.materialagent.ui.components.CodeBlock
import com.materialagent.ui.components.MarkdownText
import com.materialagent.ui.components.StreamingText
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.PlainCodeBlock
import com.materialagent.ui.components.pressScale
import com.materialagent.ui.theme.ExpressiveMotion
import kotlinx.serialization.json.Json
import java.util.Date
import kotlinx.serialization.json.JsonObject

/*
 * Transcript rows.
 *
 * Geometry carries meaning here: the user gets a bubble on the right, the agent
 * gets the full column. That asymmetry is what makes a long agent answer with
 * tools in it readable on a phone — the machine's work is never boxed into a
 * narrow column, and the human's turns are always easy to find when scrolling back.
 */

@Composable
private fun Modifier.rowPadding() = padding(horizontal = 16.dp)

/** The human's turn: a right-aligned bubble with a squared-off tail corner. */
@Composable
fun UserBubble(
    text: String,
    timestamp: Double?,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .rowPadding(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 24.dp,
                topEnd = 24.dp,
                bottomStart = 24.dp,
                bottomEnd = 6.dp,
            ),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (timestamp != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = clockTime(context, timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * The agent's turn.
 *
 * Reasoning folds away above the answer; the answer itself gets the full width and
 * renders markdown. A running turn shows the orb and the server's own status line
 * ("thinking…", the tool it just picked up) instead of a generic spinner, so the
 * user can tell *what* is happening when it takes a while.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AssistantBlock(
    entry: TranscriptEntry,
    showReasoning: Boolean,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var reasoningOpen by remember(entry.id) {
        mutableStateOf(entry.isStreaming && entry.text.isBlank())
    }

    // Auto-open reasoning while the answer is still empty, so an early stream of
    // thinking is visible without a tap; collapse it once prose arrives.
    if (entry.isStreaming && entry.text.isNotBlank() && reasoningOpen) {
        reasoningOpen = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .rowPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Every branch below can render nothing, but an empty Row is still a
        // child and would spend the 8dp Arrangement.spacedBy, so a finished
        // answer would sit 8dp lower than one that has a status line.
        if (entry.isStreaming || entry.statusLine.isNotBlank() || entry.interim) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isStreaming) {
                    AgentOrb(size = 22.dp, active = true)
                    Spacer(Modifier.width(10.dp))
                }
                if (entry.statusLine.isNotBlank()) {
                    Text(
                        text = entry.statusLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else if (entry.interim) {
                    Text(
                        text = "Thinking out loud",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (showReasoning && entry.reasoning.isNotBlank()) {
            ReasoningBlock(
                text = entry.reasoning,
                expanded = reasoningOpen,
                streaming = entry.isStreaming,
                onToggle = { reasoningOpen = !reasoningOpen },
            )
        }

        if (entry.text.isNotBlank()) {
            StreamingText(
                text = entry.text,
                streaming = entry.isStreaming,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (entry.isStreaming && entry.text.isNotBlank()) {
            StreamingCaret(style = MaterialTheme.typography.bodyLarge)
        }

        if (entry.error != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }

        if (entry.completedAt != null && !entry.isStreaming) {
            MetaPill(text = clockTime(context, entry.completedAt))
        }
    }
}

/**
 * The blinking block that says "more words are coming".
 *
 * Sized from the line it trails rather than in fixed dp, so that at large font
 * scale it grows with the text instead of reading as a stray dot.
 */
@Composable
private fun StreamingCaret(style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(700), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "caretAlpha",
    )
    val height = with(LocalDensity.current) { style.lineHeight.toDp() }
    Box(
        modifier = Modifier
            .width(height * 0.55f)
            .height(height)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                RoundedCornerShape(2.dp),
            ),
    )
}

/** Collapsible reasoning, visually quieter than the answer it precedes. */
@Composable
fun ReasoningBlock(
    text: String,
    expanded: Boolean,
    streaming: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = ExpressiveMotion.Specs.contentSize)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // The header is the whole tap target, so it has to carry the 48dp
            // minimum itself: Modifier.clickable does not expand a touch target.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (streaming) "Reasoning…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * A tool invocation.
 *
 * Collapsed it is one line — icon, tool name, what it acted on, how long it took.
 * Expanded it shows the arguments and the result, so a long agent run is
 * skimmable by default but auditable when the user wants to know.
 */
@Composable
fun ToolCard(
    tool: ToolInfo,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val container = if (tool.running) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (tool.running) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
        modifier = modifier
            .fillMaxWidth()
            .rowPadding()
            .animateContentSize(animationSpec = ExpressiveMotion.Specs.contentSize),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tool.running) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = iconForTool(tool.name),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.name.ifBlank { "tool" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = toolSummary(tool)
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (tool.durationS != null) {
                    MetaPill(text = formatDuration(tool.durationS))
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tool.args?.takeIf { it.isNotEmpty() }?.let { args ->
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PlainCodeBlock(code = prettyJson(args))
                    }
                    tool.result?.takeIf { it.isNotBlank() }?.let { result ->
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CodeBlock(language = null, code = result.take(4_000))
                    } ?: run {
                        if (tool.fromHistory && tool.args != null) {
                            // History rows carry the call but not its output; say so
                            // rather than looking like the app dropped it.
                            Text(
                                text = "History keeps the call, not its output.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Quiet inline system note — compaction, model switches, retries. */
@Composable
fun NoteRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowPadding(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** The agent's plan, with live progress. */
@Composable
fun TodosCard(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
) {
    val done = todos.count { it.status.equals("completed", ignoreCase = true) }
    // Progress only ever moves forward, so it must not overshoot: the button-press
    // spring would spring past the target and walk the bar backwards.
    val progress by animateFloatAsState(
        targetValue = if (todos.isEmpty()) 0f else done.toFloat() / todos.size,
        animationSpec = ExpressiveMotion.Specs.alpha,
        label = "todoProgress",
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .rowPadding(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Plan",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                MetaPill(text = "$done of ${todos.size}")
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            todos.forEach { todo ->
                Row(verticalAlignment = Alignment.Top) {
                    val (icon, tint) = when (todo.status.lowercase()) {
                        "completed", "done" -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                        "in_progress", "active", "running" ->
                            Icons.Rounded.Sync to MaterialTheme.colorScheme.tertiary
                        else -> Icons.Rounded.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = todo.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (todo.status.lowercase().startsWith("complete")) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * A blocking question from the agent.
 *
 * Approvals, clarifications, password prompts and secret requests all render here
 * and all demand an explicit answer — the agent is stopped until one arrives, so
 * these never scroll past unnoticed.
 */
@Composable
fun InteractionCard(
    request: InteractiveRequest,
    onAnswer: (String) -> Unit,
    onCue: (HapticCue) -> Unit,
    modifier: Modifier = Modifier,
    /** Answers one question of a clarify batch, by question id. */
    onAnswerQuestion: (questionId: String, value: String) -> Unit = { _, value -> onAnswer(value) },
) {
    val accent = when (request.kind) {
        EntryKind.SUDO, EntryKind.SECRET -> MaterialTheme.colorScheme.tertiaryContainer
        EntryKind.APPROVAL -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onAccent = when (request.kind) {
        EntryKind.SUDO, EntryKind.SECRET -> MaterialTheme.colorScheme.onTertiaryContainer
        EntryKind.APPROVAL -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    var typed by remember(request.requestId) { mutableStateOf("") }
    val answered = request.answer

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = accent,
        contentColor = onAccent,
        modifier = modifier
            .fillMaxWidth()
            .rowPadding()
            .animateContentSize(animationSpec = ExpressiveMotion.Specs.contentSize),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (request.kind) {
                        EntryKind.SUDO, EntryKind.SECRET -> Icons.Rounded.VerifiedUser
                        EntryKind.APPROVAL -> Icons.Rounded.AutoAwesome
                        else -> Icons.Rounded.Build
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = request.title.ifBlank { "The agent needs an answer" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (request.isPending) {
                    MetaPill(
                        text = if (request.questions.size > 1) {
                            "${request.unansweredCount} to answer"
                        } else {
                            "Waiting"
                        },
                        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                        content = onAccent,
                    )
                }
            }

            if (request.detail.isNotBlank()) {
                Text(
                    text = request.detail,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when {
                // A clarify arrives as a batch of questions, each answered on its
                // own; the agent stays blocked until the last one is answered.
                request.questions.isNotEmpty() -> ClarifyQuestions(
                    request = request,
                    onAnswerQuestion = onAnswerQuestion,
                    onCue = onCue,
                )

                answered != null -> Text(
                    text = "You answered: $answered",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )

                // The turn ended with this still unanswered, so it is no longer
                // answerable — say so rather than showing live buttons.
                request.expired -> Text(
                    text = "The turn ended before this was answered, so it is closed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = onAccent.copy(alpha = 0.8f),
                )

                request.kind == EntryKind.SUDO || request.kind == EntryKind.SECRET -> {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = {
                            Text(if (request.kind == EntryKind.SUDO) "Password" else "Secret")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (typed.isNotBlank()) {
                                    onCue(HapticCue.NEEDS_ATTENTION)
                                    onAnswer(typed)
                                }
                            },
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onCue(HapticCue.NEEDS_ATTENTION)
                                onAnswer(typed)
                            },
                            enabled = typed.isNotBlank(),
                        ) { Text("Send") }
                        TextButton(onClick = { onAnswer("cancel") }) { Text("Cancel") }
                    }
                }

                request.choices.isNotEmpty() -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    request.choices.forEach { choice ->
                        // Approvals are the one place the server's tokens are shown
                        // to a person, so they get spelled out. The token itself is
                        // what goes back — it is the server's vocabulary, not a label.
                        val label = if (request.kind == EntryKind.APPROVAL) {
                            approvalLabel(choice)
                        } else {
                            choice
                        }
                        val calm = choice == "deny" || choice == "cancel"
                        if (calm) {
                            TextButton(
                                onClick = {
                                    onCue(HapticCue.SENT)
                                    onAnswer(choice)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(label) }
                        } else {
                            Button(
                                onClick = {
                                    onCue(HapticCue.TOOL_DONE)
                                    onAnswer(choice)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(label) }
                        }
                    }
                }

                else -> {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Your answer") },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onCue(HapticCue.SENT)
                                onAnswer(typed)
                            },
                            enabled = typed.isNotBlank(),
                        ) { Text("Send") }
                    }
                }
            }
        }
    }
}

/**
 * Renders a clarify batch: every question with its own answer control.
 *
 * Answers already given stay visible above their unanswered siblings, because a
 * partially answered batch is the normal middle state — the agent is still
 * waiting, and the person answering needs to see what they have already said.
 */
@Composable
private fun ClarifyQuestions(
    request: InteractiveRequest,
    onAnswerQuestion: (questionId: String, value: String) -> Unit,
    onCue: (HapticCue) -> Unit,
) {
    if (request.expired) {
        Text(
            text = "The agent is no longer waiting on this, so it is closed.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        request.questions.forEach { question ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.questions.size > 1) {
                    Text(
                        text = question.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                when {
                    question.isAnswered -> Text(
                        text = "You answered: ${question.answer}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    request.expired -> Unit

                    question.choices.isNotEmpty() -> question.choices.forEach { choice ->
                        // A recommendation arrives as plain text in the choice, so
                        // the first option carries the agent's own emphasis rather
                        // than the app inventing one.
                        Button(
                            onClick = {
                                onCue(HapticCue.TOOL_DONE)
                                onAnswerQuestion(question.id, choice)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(choice) }
                    }

                    else -> {
                        var typed by remember(request.requestId, question.id) { mutableStateOf("") }
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Your answer") },
                            minLines = 1,
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                onCue(HapticCue.SENT)
                                onAnswerQuestion(question.id, typed)
                            },
                            enabled = typed.isNotBlank(),
                        ) { Text("Send") }
                    }
                }
            }
        }
    }
}

// ── Small helpers ───────────────────────────────────────────────────────────

private val prettyJson = Json { prettyPrint = true }

private fun prettyJson(obj: JsonObject): String = prettyJson.encodeToString(JsonObject.serializer(), obj)

/**
 * Spells out an `approval.request` choice for the person reading the card. The
 * server sends bare tokens (`once`, `session`, `always`, `deny`) and an unknown
 * one falls through unchanged rather than being hidden.
 */
internal fun approvalLabel(choice: String): String = when (choice) {
    "once" -> "Allow once"
    "session" -> "Allow for this session"
    "always" -> "Always allow"
    "deny" -> "Deny"
    else -> choice
}

internal fun formatDuration(seconds: Double): String = when {
    seconds < 1 -> "${(seconds * 1000).toInt()}ms"
    seconds < 60 -> String.format("%.1fs", seconds)
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}

/**
 * Formats a timestamp as a local wall-clock time.
 *
 * Takes a [Context] because the 12/24-hour shape is a user setting, not a
 * locale constant — `DateFormat.getTimeFormat` reads it from the context, and
 * passing null there is a crash.
 */
internal fun clockTime(context: Context, epochSeconds: Double): String {
    val millis = (epochSeconds * 1000).toLong()
    return DateFormat.getTimeFormat(context).format(Date(millis))
}

/**
 * A one-line gist of what a tool call is doing.
 *
 * The server's own `context` string is preferred because it is written for
 * humans, but it is sometimes a stub (search_files previews as `*`), so keys
 * that usually carry the intent are tried next.
 */
internal fun toolSummary(tool: ToolInfo): String {
    val args = tool.args
    if (args != null) {
        val key = listOf(
            "command", "cmd", "query", "pattern", "path", "file", "url",
            "prompt", "task", "description", "name",
        ).firstOrNull { args[it] != null }
        if (key != null) {
            val value = args[key].toString().trim('"')
            if (value.isNotBlank() && value != "*") return value.replace('\n', ' ')
        }
    }
    return tool.context.ifBlank { tool.preview.orEmpty() }.replace('\n', ' ')
}

/** Picks a plausible icon for a tool from its name — never a hard failure. */
internal fun iconForTool(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        lower.contains("bash") || lower.contains("shell") || lower.contains("term") ||
            lower.contains("exec") || lower.contains("command") -> Icons.Rounded.Terminal
        lower.contains("read") || lower.contains("cat") || lower.contains("file") ||
            lower.contains("glob") -> Icons.Rounded.Description
        lower.contains("write") || lower.contains("edit") || lower.contains("patch") ||
            lower.contains("apply") -> Icons.Rounded.Edit
        lower.contains("search") || lower.contains("grep") || lower.contains("find") ||
            lower.contains("query") -> Icons.Rounded.Search
        lower.contains("web") || lower.contains("http") || lower.contains("fetch") ||
            lower.contains("browse") || lower.contains("url") -> Icons.Rounded.Language
        lower.contains("image") || lower.contains("screenshot") || lower.contains("vision") ->
            Icons.Rounded.Image
        lower.contains("memory") || lower.contains("note") || lower.contains("think") ->
            Icons.Rounded.Psychology
        lower.contains("todo") || lower.contains("task") || lower.contains("plan") ->
            Icons.Rounded.Sync
        lower.contains("mcp") || lower.contains("remote") || lower.contains("cloud") ->
            Icons.Rounded.Cloud
        else -> Icons.Rounded.Build
    }
}
