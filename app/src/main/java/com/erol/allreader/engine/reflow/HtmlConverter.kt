package com.erol.allreader.engine.reflow

import com.erol.allreader.engine.DocumentFormat
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Markdown → HTML. Ham HTML kaçırılır (script/iframe çalışmaz), tehlikeli bağlantılar temizlenir. */
object MarkdownConverter {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .build()

    fun toHtml(markdown: String): String {
        val document: Node = parser.parse(markdown)
        return renderer.render(document)
    }
}

/** Düz metin → HTML (satır sonları ve boşluklar korunur). */
object PlainTextConverter {
    fun toHtml(text: String): String = "<pre class=\"plain\">" + escape(text) + "</pre>"

    fun escape(text: String): String {
        val sb = StringBuilder(text.length + text.length / 8)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** TXT ve MD için metinden HTML üretir. Office biçimleri `office/` altındaki dönüştürücülerle işlenir. */
object TextHtml {
    fun render(format: DocumentFormat, text: String): String = when (format) {
        DocumentFormat.MARKDOWN -> MarkdownConverter.toHtml(text)
        else -> PlainTextConverter.toHtml(text)
    }
}
