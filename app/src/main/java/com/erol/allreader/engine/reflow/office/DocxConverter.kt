package com.erol.allreader.engine.reflow.office

import java.io.InputStream

/** DOCX → HTML. Başlıklar, kalın/italik/altı çizili, listeler, tablolar (birleştirilmiş hücreler dahil), bağlantılar ve görseller. */
object DocxConverter {
    fun toHtml(input: InputStream): String = convert(OfficePackage.open(input, "DOCX"))

    internal fun convert(pkg: OfficePackage): String {
        val docPath = "word/document.xml"
        val doc = pkg.xml(docPath) ?: throw OfficeFormatException("Geçerli bir DOCX dosyası değil (word/document.xml yok)")
        val body = doc.child("body") ?: return EMPTY
        val ctx = Context(
            rels = pkg.relationships(docPath),
            images = ImageEmbedder(pkg),
            headingByStyle = headingStyles(pkg.xml("word/styles.xml")),
            numbering = Numbering.parse(pkg.xml("word/numbering.xml")),
        )
        val sb = StringBuilder()
        blocks(body, ctx, sb, depth = 0)
        ctx.closeList(sb)
        val html = sb.toString()
        return if (html.isBlank()) EMPTY else html
    }

    private const val EMPTY = "<p><em>Belge boş.</em></p>"
    private const val MAX_TABLE_DEPTH = 6

    private class Context(
        val rels: Map<String, OfficePackage.Relationship>,
        val images: ImageEmbedder,
        val headingByStyle: Map<String, Int>,
        val numbering: Numbering,
    ) {
        /** Açık liste: (etiket "ul"/"ol", seviye) yığını. */
        val openLists = ArrayList<String>()

        fun closeList(sb: StringBuilder) {
            while (openLists.isNotEmpty()) sb.append("</li></").append(openLists.removeAt(openLists.lastIndex)).append('>')
        }
    }

    // --- Stiller ve numaralandırma ---

    /** styleId → başlık düzeyi (1..6). `Başlık 1`/`Heading 1`/`Title` adlarından ve `basedOn` zincirinden. */
    private fun headingStyles(styles: XmlNode?): Map<String, Int> {
        if (styles == null) return emptyMap()
        val names = HashMap<String, String>()
        val basedOn = HashMap<String, String>()
        val outline = HashMap<String, Int>()
        for (s in styles.childrenNamed("style")) {
            val id = s.attr("w:styleId") ?: continue
            s.child("name")?.attr("w:val")?.let { names[id] = it.lowercase() }
            s.child("basedOn")?.attr("w:val")?.let { basedOn[id] = it }
            s.child("pPr")?.child("outlineLvl")?.attr("w:val")?.toIntOrNull()?.let { outline[id] = it + 1 }
        }
        val result = HashMap<String, Int>()
        for (id in names.keys) {
            var cur: String? = id
            var hops = 0
            while (cur != null && hops++ < 10) {
                val level = levelFromName(names[cur]) ?: outline[cur]
                if (level != null) {
                    result[id] = level.coerceIn(1, 6)
                    break
                }
                cur = basedOn[cur]
            }
        }
        return result
    }

    private fun levelFromName(name: String?): Int? {
        if (name == null) return null
        if (name == "title") return 1
        if (name == "subtitle") return 2
        val m = Regex("^(heading|başlık)\\s*(\\d)$").find(name) ?: return null
        return m.groupValues[2].toInt()
    }

    private class Numbering(
        private val numToAbstract: Map<String, String>,
        /** abstractNumId → seviye → "ol"/"ul" */
        private val formats: Map<String, Map<Int, String>>,
    ) {
        fun tag(numId: String, level: Int): String {
            val abs = numToAbstract[numId] ?: return "ul"
            return formats[abs]?.get(level) ?: "ul"
        }

        companion object {
            fun parse(root: XmlNode?): Numbering {
                if (root == null) return Numbering(emptyMap(), emptyMap())
                val formats = HashMap<String, Map<Int, String>>()
                for (a in root.childrenNamed("abstractNum")) {
                    val id = a.attr("w:abstractNumId") ?: continue
                    val levels = HashMap<Int, String>()
                    for (l in a.childrenNamed("lvl")) {
                        val ilvl = l.attr("w:ilvl")?.toIntOrNull() ?: continue
                        val fmt = l.child("numFmt")?.attr("w:val") ?: "bullet"
                        levels[ilvl] = if (fmt == "bullet" || fmt == "none") "ul" else "ol"
                    }
                    formats[id] = levels
                }
                val map = HashMap<String, String>()
                for (n in root.childrenNamed("num")) {
                    val id = n.attr("w:numId") ?: continue
                    n.child("abstractNumId")?.attr("w:val")?.let { map[id] = it }
                }
                return Numbering(map, formats)
            }
        }
    }

    // --- Bloklar ---

    private fun blocks(parent: XmlNode, ctx: Context, sb: StringBuilder, depth: Int) {
        for (node in parent.children) {
            when (node.name) {
                "p" -> paragraph(node, ctx, sb)
                "tbl" -> {
                    ctx.closeList(sb)
                    if (depth < MAX_TABLE_DEPTH) table(node, ctx, sb, depth + 1)
                }
                // İçerik denetimi / bölüm sarmalayıcıları: içlerindeki bloklar olduğu gibi akar.
                "sdt" -> node.child("sdtContent")?.let { blocks(it, ctx, sb, depth) }
                "sdtContent", "customXml", "smartTag" -> blocks(node, ctx, sb, depth)
            }
        }
    }

    private fun paragraph(p: XmlNode, ctx: Context, sb: StringBuilder) {
        val pPr = p.child("pPr")
        val numPr = pPr?.child("numPr")
        val numId = numPr?.child("numId")?.attr("w:val")
        val styleId = pPr?.child("pStyle")?.attr("w:val")
        val inner = StringBuilder()
        inlines(p, ctx, inner)
        val content = inner.toString()

        if (numId != null && numId != "0") {
            val level = numPr.child("ilvl")?.attr("w:val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            listItem(ctx, sb, numId, level, content)
            return
        }

        val heading = styleId?.let { ctx.headingByStyle[it] }
        val align = when (pPr?.child("jc")?.attr("w:val")) {
            "center" -> "center"
            "right", "end" -> "right"
            "both", "distribute" -> "justify"
            else -> null
        }
        val style = if (align != null) " style=\"text-align:$align\"" else ""
        if (content.isBlank()) {
            // Boş paragraflar atlanır; aradaki listeyi bölmesin.
            return
        }
        ctx.closeList(sb)
        if (heading != null) sb.append("<h$heading$style>").append(content).append("</h$heading>")
        else sb.append("<p$style>").append(content).append("</p>")
    }

    private fun listItem(ctx: Context, sb: StringBuilder, numId: String, level: Int, content: String) {
        val tag = ctx.numbering.tag(numId, level)
        val targetDepth = level + 1
        val open = ctx.openLists
        if (open.size >= targetDepth) {
            // Aynı veya daha sığ seviyeye dön: fazla listeleri kapat, öğeyi bitir.
            while (open.size > targetDepth) sb.append("</li></").append(open.removeAt(open.lastIndex)).append('>')
            if (open[open.lastIndex] != tag) {
                sb.append("</li></").append(open.removeAt(open.lastIndex)).append('>')
                open.add(tag)
                sb.append('<').append(tag).append("><li>")
            } else {
                sb.append("</li><li>")
            }
        } else {
            while (open.size < targetDepth) {
                open.add(tag)
                sb.append('<').append(tag).append("><li>")
            }
        }
        sb.append(content)
    }

    // --- Satır içi ---

    private fun inlines(parent: XmlNode, ctx: Context, sb: StringBuilder) {
        for (node in parent.children) {
            when (node.name) {
                "r" -> run(node, ctx, sb)
                "hyperlink" -> {
                    val rid = node.attr("r:id")
                    val rel = rid?.let { ctx.rels[it] }
                    val inner = StringBuilder()
                    inlines(node, ctx, inner)
                    val href = rel?.target?.takeIf { rel.external && isSafeLink(it) }
                    if (href != null) sb.append("<a href=\"").append(esc(href)).append("\">").append(inner).append("</a>")
                    else sb.append(inner)
                }
                "ins", "smartTag", "sdt", "sdtContent", "customXml", "fldSimple" -> {
                    if (node.name == "sdt") node.child("sdtContent")?.let { inlines(it, ctx, sb) } else inlines(node, ctx, sb)
                }
            }
        }
    }

    private fun isSafeLink(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")
    }

    private fun run(r: XmlNode, ctx: Context, sb: StringBuilder) {
        val rPr = r.child("rPr")
        val bold = rPr?.child("b")?.let { on(it) } == true
        val italic = rPr?.child("i")?.let { on(it) } == true
        val underline = rPr?.child("u")?.attr("w:val").let { it != null && it != "none" }
        val strike = rPr?.child("strike")?.let { on(it) } == true
        val vert = rPr?.child("vertAlign")?.attr("w:val")

        val inner = StringBuilder()
        for (c in r.children) {
            when (c.name) {
                "t" -> inner.append(esc(c.text))
                "tab" -> inner.append("&emsp;")
                "br", "cr" -> if (c.attr("w:type") != "page") inner.append("<br>")
                "noBreakHyphen" -> inner.append('‑')
                "drawing" -> drawing(c, ctx, inner)
                "pict" -> c.find("imagedata")?.attr("r:id")?.let { inner.append(ctx.images.imgTag(ctx.rels[it]?.target)) }
            }
        }
        if (inner.isEmpty()) return
        var out = inner.toString()
        if (bold) out = "<strong>$out</strong>"
        if (italic) out = "<em>$out</em>"
        if (underline) out = "<u>$out</u>"
        if (strike) out = "<s>$out</s>"
        if (vert == "superscript") out = "<sup>$out</sup>"
        if (vert == "subscript") out = "<sub>$out</sub>"
        sb.append(out)
    }

    /** `<w:b/>` açık, `<w:b w:val="0"/>`/`false` kapalı. */
    private fun on(n: XmlNode): Boolean {
        val v = n.attr("w:val") ?: return true
        return v != "0" && !v.equals("false", ignoreCase = true) && !v.equals("off", ignoreCase = true)
    }

    private fun drawing(d: XmlNode, ctx: Context, sb: StringBuilder) {
        val blip = d.find("blip")
        val embed = blip?.attr("r:embed")
        val alt = d.find("docPr")?.attr("descr").orEmpty()
        if (embed == null) return
        sb.append(ctx.images.imgTag(ctx.rels[embed]?.target, alt))
    }

    // --- Tablolar ---

    private class Cell(val html: String, val span: Int, val vMerge: Int /* 0 yok, 1 başlat, 2 devam */) {
        var rowSpan = 1
        var hidden = false
    }

    private fun table(tbl: XmlNode, ctx: Context, sb: StringBuilder, depth: Int) {
        val rows = ArrayList<MutableList<Cell>>()
        for (tr in tbl.childrenNamed("tr")) {
            val cells = ArrayList<Cell>()
            for (tc in tr.childrenNamed("tc")) {
                val tcPr = tc.child("tcPr")
                val span = tcPr?.child("gridSpan")?.attr("w:val")?.toIntOrNull()?.coerceIn(1, 64) ?: 1
                val vm = tcPr?.child("vMerge")
                val vMerge = when {
                    vm == null -> 0
                    vm.attr("w:val") == "restart" -> 1
                    else -> 2
                }
                val inner = StringBuilder()
                val saved = ArrayList(ctx.openLists)
                ctx.openLists.clear()
                blocks(tc, ctx, inner, depth)
                ctx.closeList(inner)
                ctx.openLists.addAll(saved)
                cells.add(Cell(inner.toString(), span, vMerge))
            }
            rows.add(cells)
        }
        if (rows.isEmpty()) return

        // Dikey birleştirme: "devam" hücreleri, aynı sütun konumundaki başlatan hücreye bağlanır.
        val starts = HashMap<Int, Cell>()
        for (row in rows) {
            var col = 0
            for (cell in row) {
                when (cell.vMerge) {
                    1 -> starts[col] = cell
                    2 -> starts[col]?.let { it.rowSpan++; cell.hidden = true }
                    else -> starts.remove(col)
                }
                col += cell.span
            }
        }

        sb.append("<div class=\"table-wrap\"><table>")
        for (row in rows) {
            sb.append("<tr>")
            for (cell in row) {
                if (cell.hidden) continue
                sb.append("<td")
                if (cell.span > 1) sb.append(" colspan=\"${cell.span}\"")
                if (cell.rowSpan > 1) sb.append(" rowspan=\"${cell.rowSpan}\"")
                sb.append('>').append(cell.html).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table></div>")
    }
}
