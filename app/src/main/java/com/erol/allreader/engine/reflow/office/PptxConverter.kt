package com.erol.allreader.engine.reflow.office

import java.io.InputStream

/** PPTX → HTML. Her slayt bir kart: başlık, metin kutuları, maddeler, tablolar, görseller ve konuşmacı notları. Birebir slayt görünümü değildir. */
object PptxConverter {
    fun toHtml(input: InputStream): String = convert(OfficePackage.open(input, "PPTX"))

    internal fun convert(pkg: OfficePackage): String {
        val presPath = "ppt/presentation.xml"
        val pres = pkg.xml(presPath) ?: throw OfficeFormatException("Geçerli bir PPTX dosyası değil (ppt/presentation.xml yok)")
        val rels = pkg.relationships(presPath)
        val slidePaths = pres.child("sldIdLst")?.childrenNamed("sldId").orEmpty()
            .mapNotNull { rels[it.attr("r:id")]?.target }
        if (slidePaths.isEmpty()) return "<p><em>Sunum boş.</em></p>"

        val images = ImageEmbedder(pkg)
        val sb = StringBuilder()
        slidePaths.forEachIndexed { i, path ->
            sb.append("<section class=\"slide\"><div class=\"slide-no\">Slayt ").append(i + 1).append("</div>")
            val slide = pkg.xml(path)
            if (slide == null) {
                sb.append("<p><em>Bu slayt okunamadı.</em></p>")
            } else {
                val slideRels = pkg.relationships(path)
                val ctx = Ctx(slideRels, images)
                if (slide.attr("show") == "0") sb.append("<p class=\"note\">(Gizli slayt)</p>")
                val tree = slide.child("cSld")?.child("spTree")
                val before = sb.length
                if (tree != null) shapes(tree, ctx, sb)
                if (sb.length == before) sb.append("<p class=\"note\">(Metin yok)</p>")
                notes(pkg, slideRels)?.let { sb.append("<div class=\"notes\"><strong>Notlar</strong><br>").append(it).append("</div>") }
            }
            sb.append("</section>")
        }
        return sb.toString()
    }

    private class Ctx(val rels: Map<String, OfficePackage.Relationship>, val images: ImageEmbedder)

    private fun notes(pkg: OfficePackage, slideRels: Map<String, OfficePackage.Relationship>): String? {
        val path = slideRels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.target ?: return null
        val root = pkg.xml(path) ?: return null
        val tree = root.child("cSld")?.child("spTree") ?: return null
        val out = StringBuilder()
        for (sp in tree.childrenNamed("sp")) {
            val ph = sp.child("nvSpPr")?.child("nvPr")?.child("ph")
            if (ph?.attr("type") != "body") continue
            val body = sp.child("txBody") ?: continue
            for (p in body.childrenNamed("p")) {
                val t = runs(p)
                if (t.isNotBlank()) {
                    if (out.isNotEmpty()) out.append("<br>")
                    out.append(t)
                }
            }
        }
        return out.toString().takeIf { it.isNotBlank() }
    }

    private fun shapes(parent: XmlNode, ctx: Ctx, sb: StringBuilder) {
        for (n in parent.children) {
            when (n.name) {
                "sp" -> textShape(n, sb)
                "pic" -> {
                    val embed = n.child("blipFill")?.child("blip")?.attr("r:embed")
                    val alt = n.child("nvPicPr")?.child("cNvPr")?.attr("descr").orEmpty()
                    if (embed != null) sb.append("<p>").append(ctx.images.imgTag(ctx.rels[embed]?.target, alt)).append("</p>")
                }
                "graphicFrame" -> n.find("tbl")?.let { table(it, sb) }
                "grpSp" -> shapes(n, ctx, sb)
            }
        }
    }

    private fun textShape(sp: XmlNode, sb: StringBuilder) {
        val body = sp.child("txBody") ?: return
        val ph = sp.child("nvSpPr")?.child("nvPr")?.child("ph")
        val type = ph?.attr("type")
        // Slayt numarası, altbilgi, tarih yer tutucuları gürültüdür.
        if (type == "sldNum" || type == "ftr" || type == "dt") return
        val isTitle = type == "title" || type == "ctrTitle"
        val isSubtitle = type == "subTitle"
        // Yer tutucu gövde metni varsayılan olarak madde işaretlidir; serbest metin kutusu değildir.
        val defaultBullet = ph != null && !isTitle && !isSubtitle

        var openLevel = -1
        fun closeLists(to: Int) {
            while (openLevel > to) {
                sb.append("</li></ul>")
                openLevel--
            }
        }
        for (p in body.childrenNamed("p")) {
            val text = runs(p)
            if (text.isBlank()) continue
            val pPr = p.child("pPr")
            val level = pPr?.attr("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            val bullet = when {
                pPr?.child("buNone") != null -> false
                pPr?.child("buChar") != null || pPr?.child("buAutoNum") != null -> true
                else -> defaultBullet
            }
            if (isTitle || isSubtitle || !bullet) {
                closeLists(-1)
                when {
                    isTitle -> sb.append("<h2>").append(text).append("</h2>")
                    isSubtitle -> sb.append("<h3>").append(text).append("</h3>")
                    else -> sb.append("<p>").append(text).append("</p>")
                }
                continue
            }
            if (level > openLevel) {
                while (openLevel < level) {
                    sb.append("<ul><li>")
                    openLevel++
                }
            } else {
                closeLists(level)
                sb.append("</li><li>")
            }
            sb.append(text)
        }
        closeLists(-1)
    }

    /** Paragrafın satır içi HTML'i: kalın/italik koşuları, satır sonları, alan metinleri. */
    private fun runs(p: XmlNode): String {
        val sb = StringBuilder()
        for (c in p.children) {
            when (c.name) {
                "r" -> {
                    val t = c.child("t")?.text ?: continue
                    val rPr = c.child("rPr")
                    var s = esc(t)
                    if (rPr?.attr("b") == "1") s = "<strong>$s</strong>"
                    if (rPr?.attr("i") == "1") s = "<em>$s</em>"
                    if (rPr?.attr("u").let { it != null && it != "none" }) s = "<u>$s</u>"
                    sb.append(s)
                }
                "fld" -> c.child("t")?.let { sb.append(esc(it.text)) }
                "br" -> sb.append("<br>")
            }
        }
        return sb.toString()
    }

    private fun table(tbl: XmlNode, sb: StringBuilder) {
        sb.append("<div class=\"table-wrap\"><table>")
        for (tr in tbl.childrenNamed("tr")) {
            sb.append("<tr>")
            for (tc in tr.childrenNamed("tc")) {
                // Birleştirmede kapsanan hücreler (hMerge/vMerge) atlanır.
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                sb.append("<td")
                tc.attr("gridSpan")?.toIntOrNull()?.takeIf { it > 1 }?.let { sb.append(" colspan=\"$it\"") }
                tc.attr("rowSpan")?.toIntOrNull()?.takeIf { it > 1 }?.let { sb.append(" rowspan=\"$it\"") }
                sb.append('>')
                val paras = tc.child("txBody")?.childrenNamed("p").orEmpty().map { runs(it) }.filter { it.isNotBlank() }
                sb.append(paras.joinToString("<br>"))
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table></div>")
    }
}
