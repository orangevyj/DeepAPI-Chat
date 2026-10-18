package com.orangestudio.deepapichat.ui.components

import androidx.compose.ui.graphics.Color
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private val styles = MdStyles(
        codeText = Color.Unspecified,
        codeSurface = Color.Unspecified,
        accent = Color.Unspecified,
        quoteBar = Color.Unspecified,
        muted = Color.Unspecified,
    )

    @Test
    fun headingsAndParagraphsAreSeparated() {
        val blocks = parseMarkdown("# Title\n\nHello **world**", styles)

        assertEquals(2, blocks.size)
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(1, heading.level)
        assertEquals("Title", heading.text.text)
        assertEquals("Hello world", (blocks[1] as MdBlock.Paragraph).text.text)
    }

    @Test
    fun inlineMarkersAreRemovedFromTheVisibleText() {
        assertEquals("a bold move", inline("a **bold** move", styles).text)
        assertEquals("code here", inline("`code` here", styles).text)
        assertTrue(inline("**bold**", styles).spanStyles.isNotEmpty())
    }

    @Test
    fun fencedCodeKeepsItsLanguageAndBody() {
        val block = parseMarkdown("```kotlin\nval x = 1\nprintln(x)\n```", styles).single() as MdBlock.Code
        assertEquals("kotlin", block.language)
        assertEquals("val x = 1\nprintln(x)", block.code.trim())
    }

    @Test
    fun anUnterminatedFenceStillBecomesCode() {
        val blocks = parseMarkdown("Some text\n```\nval y = 2", styles)
        assertTrue(blocks.first() is MdBlock.Paragraph)
        assertEquals("val y = 2", (blocks.last() as MdBlock.Code).code.trim())
    }

    @Test
    fun listsAreRecognised() {
        val blocks = parseMarkdown("- one\n- two\n\n1. first\n2. second", styles)
        assertTrue(blocks.any { it is MdBlock.Bullet })
        assertTrue(blocks.any { it is MdBlock.Numbered })
    }

    @Test
    fun quotesAreRecognised() {
        val block = parseMarkdown("> quoted", styles).single() as MdBlock.Quote
        assertEquals("quoted", block.text.text)
    }

    @Test
    fun emptyInputProducesNoBlocks() {
        assertTrue(parseMarkdown("   \n\n  ", styles).isEmpty())
    }

    @Test
    fun aPipeTableBecomesATableBlock() {
        val table = parseMarkdown(
            """
            | Item | Cost |
            | --- | ---: |
            | Tea | 2 |
            | Cake | 5 |
            """.trimIndent(),
            styles,
        ).single() as MdBlock.Table

        assertEquals(listOf("Item", "Cost"), table.headers.map { it.text })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Tea", "2"), table.rows[0].map { it.text })
        assertEquals(listOf("Cake", "5"), table.rows[1].map { it.text })
    }

    @Test
    fun pipesWithoutADividerRowStayPlainText() {
        assertTrue(parseMarkdown("a | b", styles).single() is MdBlock.Paragraph)
    }

    @Test
    fun aChartFenceBecomesAChart() {
        val chart = parseMarkdown(
            "```chart\n{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"values\":[1,2]}\n```",
            styles,
        ).single() as MdBlock.Chart

        assertEquals(ChartSpec.Kind.Bar, chart.spec.kind)
        assertEquals(listOf("a", "b"), chart.spec.points.map { it.label })
    }

    @Test
    fun aChartWeCannotReadStaysVisibleAsCode() {
        assertTrue(parseMarkdown("```chart\nnot json at all\n```", styles).single() is MdBlock.Code)
    }

    @Test
    fun fenceTagsMapOntoGrammars() {
        assertEquals(SyntaxLanguage.KOTLIN, syntaxLanguage("kt"))
        assertEquals(SyntaxLanguage.PYTHON, syntaxLanguage("Python"))
        assertEquals(SyntaxLanguage.SHELL, syntaxLanguage("bash"))
        // JSON rides the JavaScript scanner, which is the whole reason it is here.
        assertEquals(SyntaxLanguage.JAVASCRIPT, syntaxLanguage("json"))
        // No grammar exists for these, and the label is kept without colour.
        assertNull(syntaxLanguage("sql"))
        assertNull(syntaxLanguage("yaml"))
        assertNull(syntaxLanguage(""))
    }

    @Test
    fun colouringNeverDropsText() {
        val code = "fun main() { println(\"hi\") }"
        val coloured = colourisedCode(code, "kotlin", SyntaxThemes.default(true))
        assertEquals(code, coloured!!.text)
    }
}
