package com.erol.allreader.engine.reflow

import com.erol.allreader.engine.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlConvertersTest {
    @Test
    fun markdownRendersBasicSyntax() {
        val html = MarkdownConverter.toHtml("# Başlık\n\n**kalın** ve *eğik*\n\n- bir\n- iki")
        assertTrue(html.contains("<h1>Başlık</h1>"))
        assertTrue(html.contains("<strong>kalın</strong>"))
        assertTrue(html.contains("<em>eğik</em>"))
        assertTrue(html.contains("<li>bir</li>"))
    }

    @Test
    fun markdownRendersTablesStrikethroughAndTaskLists() {
        val html = MarkdownConverter.toHtml("| Ad | Değer |\n|----|-------|\n| Şişe | İçki |\n\n~~sil~~\n\n- [x] bitti\n- [ ] bekliyor")
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>Şişe</td>"))
        assertTrue(html.contains("<del>sil</del>"))
        assertTrue(html.contains("type=\"checkbox\""))
    }

    @Test
    fun markdownEscapesRawHtml() {
        val html = MarkdownConverter.toHtml("<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun markdownDropsJavascriptLinks() {
        val html = MarkdownConverter.toHtml("[tıkla](javascript:alert(1))")
        assertFalse(html.contains("javascript:"))
    }

    @Test
    fun emptyMarkdownIsEmpty() {
        assertEquals("", MarkdownConverter.toHtml(""))
    }

    @Test
    fun plainTextIsEscapedAndKeepsNewlines() {
        val html = PlainTextConverter.toHtml("a < b & \"c\"\nsatır2")
        assertTrue(html.startsWith("<pre"))
        assertTrue(html.contains("a &lt; b &amp; &quot;c&quot;\nsatır2"))
    }

    @Test
    fun textHtmlPicksConverterByFormat() {
        assertTrue(TextHtml.render(DocumentFormat.MARKDOWN, "# x").contains("<h1>"))
        assertTrue(TextHtml.render(DocumentFormat.TXT, "# x").contains("# x"))
    }

    @Test
    fun readerHtmlAppliesThemeColors() {
        val dark = ReaderHtml.wrap("<p>x</p>", ReaderTheme.DARK, systemDark = false)
        assertTrue(dark.contains("#121212"))
        val system = ReaderHtml.wrap("<p>x</p>", ReaderTheme.SYSTEM, systemDark = true)
        assertTrue(system.contains("#121212"))
        val sepia = ReaderHtml.wrap("<p>x</p>", ReaderTheme.SEPIA, systemDark = true)
        assertTrue(sepia.contains("#f4ecd8"))
        assertTrue(sepia.contains("<p>x</p>"))
    }
}
