package com.erol.allreader.engine.reflow.office

import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * XLSX → HTML. Her görünür çalışma sayfası ayrı bir bölüm/tablo olur (JavaScript kapalı olduğundan sekme yok).
 * Hücre değerleri dosyadaki önbellekli sonuçtur (formüller hesaplanmaz). Tarih biçimli sayılar tarihe çevrilir.
 */
object XlsxConverter {
    const val MAX_ROWS = 5000
    const val MAX_COLS = 100

    fun toHtml(input: InputStream): String = convert(OfficePackage.open(input, "XLSX"))

    internal fun convert(pkg: OfficePackage): String {
        val wbPath = "xl/workbook.xml"
        val wb = pkg.xml(wbPath) ?: throw OfficeFormatException("Geçerli bir XLSX dosyası değil (xl/workbook.xml yok)")
        val rels = pkg.relationships(wbPath)
        val date1904 = wb.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        val shared = sharedStrings(pkg.xml("xl/sharedStrings.xml"))
        val dateStyles = dateStyleIndexes(pkg.xml("xl/styles.xml"))
        val percentStyles = percentStyleIndexes(pkg.xml("xl/styles.xml"))

        val sheets = wb.child("sheets")?.childrenNamed("sheet").orEmpty()
        val visible = sheets.filter { it.attr("state") != "hidden" && it.attr("state") != "veryHidden" }
        if (visible.isEmpty()) return "<p><em>Çalışma kitabı boş.</em></p>"

        val sb = StringBuilder()
        if (visible.size > 1) {
            sb.append("<p class=\"sheet-index\">")
            visible.forEachIndexed { i, s ->
                if (i > 0) sb.append(" · ")
                sb.append(esc(s.attr("name") ?: "Sayfa ${i + 1}"))
            }
            sb.append("</p>")
        }
        visible.forEachIndexed { i, sheet ->
            val name = sheet.attr("name") ?: "Sayfa ${i + 1}"
            val target = rels[sheet.attr("r:id")]?.target
            sb.append("<section class=\"sheet\"><h2>").append(esc(name)).append("</h2>")
            val xml = target?.let { pkg.xml(it) }
            if (xml == null) sb.append("<p><em>Bu sayfa okunamadı.</em></p>")
            else sheetTable(xml, shared, dateStyles, percentStyles, date1904, sb)
            sb.append("</section>")
        }
        return sb.toString()
    }

    private fun sharedStrings(root: XmlNode?): List<String> {
        if (root == null) return emptyList()
        return root.childrenNamed("si").map { si -> richText(si) }
    }

    /** `<si>` / `<is>`: düz `<t>` ya da biçimli `<r><t>` parçalarının birleşimi (fonetik `rPh` hariç). */
    private fun richText(node: XmlNode): String {
        val sb = StringBuilder()
        for (c in node.children) {
            when (c.name) {
                "t" -> sb.append(c.text)
                "r" -> c.child("t")?.let { sb.append(it.text) }
            }
        }
        return sb.toString()
    }

    // --- Stiller ---

    private fun cellXfNumFmts(styles: XmlNode?): List<Int> =
        styles?.child("cellXfs")?.childrenNamed("xf")?.map { it.attr("numFmtId")?.toIntOrNull() ?: 0 }.orEmpty()

    private fun customFormats(styles: XmlNode?): Map<Int, String> {
        val out = HashMap<Int, String>()
        styles?.child("numFmts")?.childrenNamed("numFmt")?.forEach { n ->
            val id = n.attr("numFmtId")?.toIntOrNull()
            val code = n.attr("formatCode")
            if (id != null && code != null) out[id] = code
        }
        return out
    }

    private fun dateStyleIndexes(styles: XmlNode?): Set<Int> {
        val custom = customFormats(styles)
        val out = HashSet<Int>()
        cellXfNumFmts(styles).forEachIndexed { index, id ->
            val isDate = when (id) {
                in 14..22, in 45..47 -> true
                else -> custom[id]?.let { looksLikeDateFormat(it) } == true
            }
            if (isDate) out.add(index)
        }
        return out
    }

    private fun percentStyleIndexes(styles: XmlNode?): Set<Int> {
        val custom = customFormats(styles)
        val out = HashSet<Int>()
        cellXfNumFmts(styles).forEachIndexed { index, id ->
            if (id == 9 || id == 10 || custom[id]?.contains('%') == true) out.add(index)
        }
        return out
    }

    /** Tırnak içi metin ve [renk]/[koşul] bölümleri dışında d/m/y/h/s harfi geçiyorsa tarih/saat biçimidir. */
    internal fun looksLikeDateFormat(code: String): Boolean {
        val stripped = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\\\."), "")
        return Regex("[dmyhs]", RegexOption.IGNORE_CASE).containsMatchIn(stripped) &&
            !stripped.contains("General", ignoreCase = true)
    }

    // --- Sayfa ---

    private class Span(val rowSpan: Int, val colSpan: Int)

    private fun sheetTable(
        sheet: XmlNode,
        shared: List<String>,
        dateStyles: Set<Int>,
        percentStyles: Set<Int>,
        date1904: Boolean,
        sb: StringBuilder,
    ) {
        // Birleştirilmiş hücreler: sol üst → yayılım; diğerleri gizli.
        val spans = HashMap<Long, Span>()
        val covered = HashSet<Long>()
        sheet.child("mergeCells")?.childrenNamed("mergeCell")?.forEach { m ->
            val ref = m.attr("ref") ?: return@forEach
            val parts = ref.split(':')
            if (parts.size != 2) return@forEach
            val a = parseRef(parts[0]) ?: return@forEach
            val b = parseRef(parts[1]) ?: return@forEach
            val rows = b.first - a.first + 1
            val cols = b.second - a.second + 1
            if (rows < 1 || cols < 1 || rows * cols > 100_000) return@forEach
            spans[key(a.first, a.second)] = Span(rows, cols)
            for (r in a.first..b.first) for (c in a.second..b.second) {
                if (r != a.first || c != a.second) covered.add(key(r, c))
            }
        }

        val data = sheet.child("sheetData")
        val rowNodes = data?.childrenNamed("row").orEmpty()
        if (rowNodes.isEmpty()) {
            sb.append("<p><em>Bu sayfa boş.</em></p>")
            return
        }

        // Önce hücreleri (satır → sütun → metin) topla, sütun sayısını bul.
        val grid = java.util.TreeMap<Int, java.util.TreeMap<Int, String>>()
        var maxCol = 0
        var truncatedRows = false
        var truncatedCols = false
        var autoRow = 0
        for (row in rowNodes) {
            val rIdx = (row.attr("r")?.toIntOrNull() ?: (autoRow + 1)) - 1
            autoRow = rIdx + 1
            if (rIdx >= MAX_ROWS) {
                truncatedRows = true
                break
            }
            var autoCol = 0
            for (c in row.childrenNamed("c")) {
                val ref = c.attr("r")?.let { parseRef(it) }
                val cIdx = ref?.second ?: autoCol
                autoCol = cIdx + 1
                if (cIdx >= MAX_COLS) {
                    truncatedCols = true
                    continue
                }
                val text = cellText(c, shared, dateStyles, percentStyles, date1904)
                if (text.isNotEmpty()) {
                    grid.getOrPut(rIdx) { java.util.TreeMap() }[cIdx] = text
                    if (cIdx + 1 > maxCol) maxCol = cIdx + 1
                }
            }
        }
        if (grid.isEmpty()) {
            sb.append("<p><em>Bu sayfa boş.</em></p>")
            return
        }
        // Birleştirme alanları sütun sayısını genişletebilir.
        spans.forEach { (k, s) -> val c = (k and 0xFFFF).toInt(); if (c + s.colSpan > maxCol) maxCol = minOf(c + s.colSpan, MAX_COLS) }

        val lastRow = grid.lastKey()
        sb.append("<div class=\"table-wrap\"><table class=\"sheet-table\">")
        for (r in 0..lastRow) {
            val cells = grid[r]
            // Baştaki boş satırları da koru ki satır konumları kaymasın; ama tamamen boş satırı ince bırak.
            sb.append("<tr>")
            if (cells == null && spans.keys.none { (it shr 16).toInt() == r }) {
                sb.append("<td class=\"empty\" colspan=\"$maxCol\">&nbsp;</td></tr>")
                continue
            }
            for (c in 0 until maxCol) {
                val k = key(r, c)
                if (k in covered) continue
                val span = spans[k]
                sb.append("<td")
                if (span != null) {
                    if (span.colSpan > 1) sb.append(" colspan=\"${minOf(span.colSpan, maxCol - c)}\"")
                    if (span.rowSpan > 1) sb.append(" rowspan=\"${span.rowSpan}\"")
                }
                val text = cells?.get(c)
                sb.append('>')
                if (text != null) sb.append(esc(text).replace("\n", "<br>"))
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table></div>")
        if (truncatedRows || truncatedCols) {
            sb.append("<p class=\"note\">Çok büyük sayfa: yalnızca ilk $MAX_ROWS satır ve $MAX_COLS sütun gösteriliyor.</p>")
        }
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 16) or col.toLong()

    /** "B3" → (satır 2, sütun 1), 0 tabanlı. */
    internal fun parseRef(ref: String): Pair<Int, Int>? {
        var i = 0
        var col = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        if (i == 0 || i == ref.length) return null
        val row = ref.substring(i).toIntOrNull() ?: return null
        if (row < 1 || col < 1 || col > 16384) return null
        return (row - 1) to (col - 1)
    }

    private fun cellText(c: XmlNode, shared: List<String>, dateStyles: Set<Int>, percentStyles: Set<Int>, date1904: Boolean): String {
        val type = c.attr("t")
        val v = c.child("v")?.text
        return when (type) {
            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
            "inlineStr" -> c.child("is")?.let { richText(it) }.orEmpty()
            "str" -> v.orEmpty()
            "b" -> when (v?.trim()) {
                "1" -> "DOĞRU"
                "0" -> "YANLIŞ"
                else -> ""
            }
            "e" -> v.orEmpty()
            "d" -> v.orEmpty().replace('T', ' ')
            else -> {
                val raw = v?.trim().orEmpty()
                if (raw.isEmpty()) return ""
                val style = c.attr("s")?.toIntOrNull()
                val num = raw.toDoubleOrNull() ?: return raw
                when {
                    style != null && style in dateStyles -> formatDate(num, date1904) ?: formatNumber(raw)
                    style != null && style in percentStyles -> formatNumber(BigDecimal(raw).multiply(BigDecimal(100)).toPlainString()) + "%"
                    else -> formatNumber(raw)
                }
            }
        }
    }

    /** Excel "Genel" biçimi gibi 11 anlamlı basamağa yuvarlar (0.30000000000000004 → 0.3), bilimsel gösterim kullanmaz. */
    internal fun formatNumber(raw: String): String = try {
        val bd = BigDecimal(raw).round(MathContext(11)).stripTrailingZeros()
        if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
    } catch (e: NumberFormatException) {
        raw
    }

    internal fun formatDate(serial: Double, date1904: Boolean): String? {
        if (serial.isNaN() || serial < 0 || serial > 2_958_465) return null
        val epoch = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        val days = Math.floor(serial).toLong()
        val seconds = Math.round((serial - days) * 86400.0)
        val dt = LocalDateTime.of(epoch, java.time.LocalTime.MIDNIGHT).plusDays(days).plusSeconds(seconds)
        return if (days == 0L && !date1904) {
            dt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        } else if (seconds == 0L) {
            dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } else {
            dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        }
    }
}
