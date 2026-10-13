package com.orangestudio.deepapichat.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/**
 * Maps a fence's language tag onto the highlighter's language set.
 *
 * The library covers KOTLIN, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, SHELL, GO,
 * RUST, CPP, CSHARP, PHP, RUBY, SWIFT, DART, PERL and COFFEESCRIPT, and nothing
 * else: it has no JSON, SQL, XML or YAML grammar. Those fences keep their label
 * and render uncoloured rather than pretending to a grammar that isn't there.
 *
 * JSON is the one deliberate exception. JavaScript's scanner colours exactly what
 * a JSON reader wants - strings, numbers, and true/false/null - and because a JSON
 * object's keys are always quoted, nothing in a well-formed document can be
 * mistaken for a keyword.
 *
 * Aliases are the ones people actually write in fences (`kt`, `py`, `sh`, `c++`),
 * because the tag is whatever the model chose to type.
 */
fun syntaxLanguage(tag: String): SyntaxLanguage? = when (tag.trim().lowercase()) {
    "kt", "kts", "kotlin" -> SyntaxLanguage.KOTLIN
    "java" -> SyntaxLanguage.JAVA
    "py", "python" -> SyntaxLanguage.PYTHON
    "js", "jsx", "mjs", "cjs", "node", "javascript" -> SyntaxLanguage.JAVASCRIPT
    "ts", "tsx", "typescript" -> SyntaxLanguage.TYPESCRIPT
    "json", "jsonc" -> SyntaxLanguage.JAVASCRIPT
    "sh", "bash", "zsh", "shell", "console", "shell-session", "terminal" -> SyntaxLanguage.SHELL
    "go", "golang" -> SyntaxLanguage.GO
    "rs", "rust" -> SyntaxLanguage.RUST
    "c", "h", "cc", "hh", "cpp", "c++", "hpp", "objc" -> SyntaxLanguage.CPP
    "cs", "csharp", "c#" -> SyntaxLanguage.CSHARP
    "php" -> SyntaxLanguage.PHP
    "rb", "ruby" -> SyntaxLanguage.RUBY
    "swift" -> SyntaxLanguage.SWIFT
    "dart" -> SyntaxLanguage.DART
    "pl", "perl" -> SyntaxLanguage.PERL
    "coffee", "coffeescript" -> SyntaxLanguage.COFFEESCRIPT
    else -> null
}

/**
 * Colours [code] for the fence tag [tag], or returns null when the tag names no
 * grammar we have so the caller can fall back to plain monospace.
 *
 * The library's highlight list is treated as a set of spans, not as the text:
 * anything the list doesn't cover is appended from [code] itself and every range
 * is clamped. A phrase the highlighter misses, or a list that turns out not to
 * cover the whole input, therefore cannot drop characters the model wrote.
 */
fun colourisedCode(code: String, tag: String, theme: SyntaxTheme): AnnotatedString? {
    val language = syntaxLanguage(tag) ?: return null
    val highlights = runCatching {
        Highlights.Builder()
            .code(code)
            .language(language)
            .theme(theme)
            .build()
            .getHighlights()
    }.getOrNull() ?: return null

    return buildAnnotatedString {
        var cursor = 0
        for (highlight in highlights.sortedBy { it.location.start }) {
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (start > cursor) append(code.substring(cursor, start))
            if (end > start) {
                val text = code.substring(start, end)
                when (highlight) {
                    is ColorHighlight -> withStyle(SpanStyle(color = opaque(highlight.rgb))) { append(text) }
                    is BoldHighlight -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text) }
                }
            }
            if (end > cursor) cursor = end
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}

/**
 * The theme's colours are RGB triples with no alpha byte, so forcing the top byte
 * on is what makes them visible; used as-is they would come out fully
 * transparent. It is also a no-op when the value already carries an alpha of 0xFF,
 * so it is safe whichever way the theme was written.
 */
private fun opaque(rgb: Int): Color = Color(rgb or 0xFF000000.toInt())
