package com.materialagent.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.materialagent.ui.theme.CodeTextStyle

/*
 * A deliberately small Markdown renderer.
 *
 * Agent output is mostly prose with three things worth showing properly: fenced
 * code, inline code and lists. This handles those plus headings, quotes, rules
 * and links, and does *not* attempt to be CommonMark — a full parser would be a
 * dependency and a maintenance bill for no visible gain on a phone. The inline
 * scanner is single-pass and never throws on malformed input, because agent
 * prose routinely contains stray asterisks and unclosed backticks.
 */

private sealed interface Block {
    data class Para(val spans: AnnotatedString) : Block
    data class Heading(val level: Int, val spans: AnnotatedString) : Block
    data class Code(val language: String?, val body: String) : Block
    data class Item(
        val spans: AnnotatedString,
        val marker: String,
        val depth: Int,
    ) : Block
    data class Quote(val spans: AnnotatedString) : Block
    data object Rule : Block
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Para -> Text(text = block.spans, style = style, color = color)

                is Block.Heading -> Text(
                    text = block.spans,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is Block.Code -> CodeBlock(language = block.language, code = block.body)

                is Block.Item -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.depth * 14).dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(
                        text = block.spans,
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }

                is Block.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = block.spans,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }

                Block.Rule -> Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

/** Monospace block with a copy affordance that confirms itself in place. */
@Composable
fun CodeBlock(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
) {
    var copied by remember(code) { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language?.ifBlank { "code" } ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        manager.setPrimaryClip(ClipData.newPlainText("code", code))
                        copied = true
                    },
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Done else Icons.Rounded.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = code,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 10.dp)
                    .animateContentSize(),
            )
        }
    }
}

/** A bordered code surface without the copy chrome — for tool arguments. */
@Composable
fun PlainCodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = code,
            style = CodeTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ── Parsing ─────────────────────────────────────────────────────────────────

private val FENCE = Regex("^\\s*```\\s*(\\S*)\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")

private fun parseMarkdown(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.replace("\r\n", "\n").split("\n")
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += Block.Para(inline(paragraph.toString()))
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index].trimEnd()

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            flushParagraph()
            val language = fence.groupValues[1].ifBlank { null }
            val body = StringBuilder()
            index += 1
            while (index < lines.size && FENCE.matchEntire(lines[index].trimEnd()) == null) {
                body.appendLine(lines[index])
                index += 1
            }
            if (index < lines.size) index += 1 // closing fence
            blocks += Block.Code(language, body.toString().trimEnd('\n'))
            continue
        }

        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val ordered = ORDERED.matchEntire(line)
        val quote = QUOTE.matchEntire(line)

        when {
            line.isBlank() -> {
                flushParagraph()
                index += 1
            }

            RULE.matches(line) -> {
                flushParagraph()
                blocks += Block.Rule
                index += 1
            }

            heading != null -> {
                flushParagraph()
                blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2].trim()))
                index += 1
            }

            bullet != null -> {
                flushParagraph()
                blocks += Block.Item(
                    spans = inline(bullet.groupValues[2]),
                    marker = if (bullet.groupValues[1].length >= 2) "◦" else "•",
                    depth = (bullet.groupValues[1].length / 2).coerceAtMost(3),
                )
                index += 1
            }

            ordered != null -> {
                flushParagraph()
                blocks += Block.Item(
                    spans = inline(ordered.groupValues[3]),
                    marker = "${ordered.groupValues[2]}.",
                    depth = (ordered.groupValues[1].length / 2).coerceAtMost(3),
                )
                index += 1
            }

            quote != null -> {
                flushParagraph()
                blocks += Block.Quote(inline(quote.groupValues[1]))
                index += 1
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
                index += 1
            }
        }
    }
    flushParagraph()
    return blocks
}

/** Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url). */
private fun inline(source: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < source.length) {
        val char = source[i]
        when {
            char == '`' -> {
                val end = source.indexOf('`', startIndex = i + 1)
                if (end == -1) {
                    append(source.substring(i))
                    return@buildAnnotatedString
                }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x2A808080))) {
                    append(source.substring(i + 1, end))
                }
                i = end + 1
            }

            source.startsWith("**", i) || source.startsWith("__", i) -> {
                val token = source.substring(i, i + 2)
                val end = source.indexOf(token, startIndex = i + 2)
                if (end == -1) {
                    append(char)
                    i += 1
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(source.substring(i + 2, end))
                    }
                    i = end + 2
                }
            }

            source.startsWith("~~", i) -> {
                val end = source.indexOf("~~", startIndex = i + 2)
                if (end == -1) {
                    append(char)
                    i += 1
                } else {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(source.substring(i + 2, end))
                    }
                    i = end + 2
                }
            }

            char == '*' || char == '_' -> {
                val end = source.indexOf(char, startIndex = i + 1)
                if (end <= i + 1) {
                    append(char)
                    i += 1
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(source.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }

            char == '[' -> {
                val close = source.indexOf(']', startIndex = i + 1)
                val open = if (close == -1) -1 else source.indexOf('(', startIndex = close)
                val end = if (open == -1) -1 else source.indexOf(')', startIndex = open)
                if (close == -1 || open == -1 || end == -1) {
                    append(char)
                    i += 1
                } else {
                    val label = source.substring(i + 1, close)
                    val url = source.substring(open + 1, end)
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF6FA8FF),
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(label)
                        }
                    }
                    i = end + 1
                }
            }

            else -> {
                append(char)
                i += 1
            }
        }
    }
}
