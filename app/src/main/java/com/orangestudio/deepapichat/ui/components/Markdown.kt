package com.orangestudio.deepapichat.ui.components

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.snipme.highlights.model.SyntaxThemes

/**
 * A deliberately small Markdown renderer.
 *
 * Assistant output is Markdown, and a full CommonMark implementation would be a
 * large dependency for the subset that actually appears in chat replies: fenced
 * code, inline code, bold, italic, strikethrough, headings, lists, quotes and
 * rules. Anything unrecognised is shown verbatim, and an unterminated code fence
 * renders as code so text doesn't flicker while streaming.
 */
sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Bullet(val text: AnnotatedString, val depth: Int) : MdBlock
    data class Numbered(val marker: String, val text: AnnotatedString, val depth: Int) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val headers: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data class Chart(val spec: ChartSpec) : MdBlock
    data object Rule : MdBlock
}

/** Theme colours the parser needs, passed in so parsing stays a pure function. */
data class MdStyles(
    val codeText: Color,
    val codeSurface: Color,
    val accent: Color,
    val quoteBar: Color,
    val muted: Color,
    /** Outline for the bordered code, table and chart blocks. */
    val border: Color = quoteBar,
)

@Composable
fun rememberMdStyles(): MdStyles = MdStyles(
    codeText = MaterialTheme.colorScheme.onSurfaceVariant,
    codeSurface = MaterialTheme.colorScheme.surfaceVariant,
    accent = MaterialTheme.colorScheme.primary,
    quoteBar = MaterialTheme.colorScheme.outlineVariant,
    muted = MaterialTheme.colorScheme.onSurfaceVariant,
    border = MaterialTheme.colorScheme.outlineVariant,
)

private val INLINE = Regex(
    "(`[^`]+`)" +
        "|(\\*\\*[^*]+\\*\\*)" +
        "|(__[^_]+__)" +
        "|(~~[^~]+~~)" +
        "|(\\*[^*\\n]+\\*)" +
        "|(_[^_\\n]+_)" +
        "|(\\[[^\\]]+\\]\\([^)\\s]+\\))",
)

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

/** A GFM alignment cell: `---`, `:--`, `--:` or `:-:`. */
private val TABLE_CELL_DIVIDER = Regex("^:?-{2,}:?$")

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|')

private fun isTableDivider(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { TABLE_CELL_DIVIDER.matches(it.trim()) }
}

/** Parses [text] into renderable blocks. Pure; safe to call on every keystroke. */
fun parseMarkdown(text: String, styles: MdStyles): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    val lines = text.replace("\r\n", "\n").split("\n")

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(inline(paragraph.toString(), styles)))
            paragraph.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val trimmed = raw.trimStart()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val language = trimmed.removePrefix("```").trim()
            val body = ArrayList<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                body.add(lines[index])
                index++
            }
            // A missing closing fence means we are mid-stream; render what we have.
            if (index < lines.size) index++
            val bodyText = body.joinToString("\n")
            val chart = if (isChartFence(language)) parseChart(bodyText) else null
            // A chart we could not read falls back to code so it stays readable.
            blocks.add(chart?.let { MdBlock.Chart(it) } ?: MdBlock.Code(language, bodyText))
            continue
        }

        if (trimmed.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        // A row of pipes over an alignment row is a table; anything else keeps
        // its pipes as ordinary text.
        if (trimmed.contains('|') && index + 1 < lines.size && isTableDivider(lines[index + 1])) {
            flushParagraph()
            val headers = splitTableRow(trimmed).map { inline(it.trim(), styles) }
            index += 2
            val rows = ArrayList<List<AnnotatedString>>()
            while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
                rows.add(splitTableRow(lines[index]).map { inline(it.trim(), styles) })
                index++
            }
            blocks.add(MdBlock.Table(headers, rows))
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MdBlock.Rule)
            index++
            continue
        }

        HEADING.find(trimmed)?.let { match ->
            flushParagraph()
            blocks.add(MdBlock.Heading(match.groupValues[1].length, inline(match.groupValues[2].trim(), styles)))
            index++
            return@let
        }?.also { continue }

        BULLET.find(raw)?.let { match ->
            flushParagraph()
            blocks.add(MdBlock.Bullet(inline(match.groupValues[2], styles), match.groupValues[1].length / 2))
            index++
            return@let
        }?.also { continue }

        NUMBERED.find(raw)?.let { match ->
            flushParagraph()
            blocks.add(
                MdBlock.Numbered(
                    marker = match.groupValues[2] + ".",
                    text = inline(match.groupValues[3], styles),
                    depth = match.groupValues[1].length / 2,
                ),
            )
            index++
            return@let
        }?.also { continue }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks.add(MdBlock.Quote(inline(trimmed.removePrefix(">").trim(), styles)))
            index++
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(trimmed.trimEnd())
        index++
    }
    flushParagraph()
    return blocks
}

/** Expands inline markup into a styled [AnnotatedString]. */
fun inline(text: String, styles: MdStyles): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = styles.codeSurface,
                    color = styles.codeText,
                ),
            ) { append(token.substring(1, token.length - 1)) }

            token.startsWith("**") || token.startsWith("__") -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold),
            ) { append(token.substring(2, token.length - 2)) }

            token.startsWith("~~") -> withStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
            ) { append(token.substring(2, token.length - 2)) }

            token.startsWith("[") -> withStyle(
                SpanStyle(color = styles.accent, textDecoration = TextDecoration.Underline),
            ) { append(token.substringAfter('[').substringBefore(']')) }

            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(token.substring(1, token.length - 1))
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val styles = rememberMdStyles()
    val blocks = remember(text, styles) { parseMarkdown(text, styles) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(block.text, style = style)

                is MdBlock.Heading -> Text(
                    text = block.text,
                    style = style.copy(
                        fontSize = style.fontSize * (if (block.level <= 2) 1.25f else 1.12f),
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = style.lineHeight * 1.2f,
                    ),
                )

                is MdBlock.Bullet -> MarkedLine(marker = "•", text = block.text, depth = block.depth, style = style)

                is MdBlock.Numbered -> MarkedLine(marker = block.marker, text = block.text, depth = block.depth, style = style)

                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(styles.quoteBar),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = block.text, style = style.copy(color = styles.muted))
                }

                is MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = styles.quoteBar,
                )

                is MdBlock.Code -> CodeBlock(block, styles, style)

                is MdBlock.Table -> TableBlock(block, styles, style)

                is MdBlock.Chart -> ChartBlock(block.spec, styles, style)
            }
        }
    }
}

@Composable
private fun MarkedLine(marker: String, text: AnnotatedString, depth: Int, style: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
        Text(text = marker, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = style)
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, styles: MdStyles, style: TextStyle) {
    val outline = RoundedCornerShape(8.dp)
    // The highlighter's palettes are tuned for either a dark or a light page, so
    // pick the side the app is actually on instead of one fixed theme.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val highlighted = remember(block.code, block.language, dark) {
        colourisedCode(block.code, block.language, SyntaxThemes.default(dark))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outline)
            .border(1.dp, styles.border, outline)
            .background(styles.codeSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Whatever the model put after the fence. Naming the language is the
            // model's job, so a bare fence is called out rather than guessed at.
            Text(
                text = block.language.ifBlank { "no language given" },
                style = MaterialTheme.typography.labelSmall,
                color = styles.muted,
                modifier = Modifier.weight(1f),
            )
            CopyButton(block.code)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = highlighted ?: AnnotatedString(block.code),
                style = style.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = style.fontSize * 0.92f,
                    color = styles.codeText,
                ),
            )
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
        },
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy code",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, styles: MdStyles, style: TextStyle) {
    val outline = RoundedCornerShape(8.dp)
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    // One scroll state shared by every row, so the header keeps its columns over
    // the data when a wide table is swiped.
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outline)
            .border(1.dp, styles.border, outline),
    ) {
        TableRow(block.headers, columns, styles, style, scroll, header = true)
        block.rows.forEach { row ->
            HorizontalDivider(color = styles.border)
            TableRow(row, columns, styles, style, scroll, header = false)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    columns: Int,
    styles: MdStyles,
    style: TextStyle,
    scroll: ScrollState,
    header: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .background(if (header) styles.codeSurface else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed columns rather than weights: a Row that scrolls has no finite
        // width to divide, and even columns keep the header over its data.
        repeat(columns) { column ->
            Text(
                text = cells.getOrNull(column) ?: AnnotatedString(""),
                style = if (header) style.copy(fontWeight = FontWeight.SemiBold) else style,
                modifier = Modifier
                    .width(132.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
