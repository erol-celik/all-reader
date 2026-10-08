package com.erol.allreader.engine.reflow.office

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfficeConvertersTest {
    private fun zip(vararg parts: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(if (content is ByteArray) content else (content as String).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val ns = "xmlns:w=\"w\" xmlns:r=\"r\""
    private fun docx(body: String, vararg extra: Pair<String, Any>) =
        zip("word/document.xml" to "<w:document $ns><w:body>$body</w:body></w:document>", *extra)

    private fun png(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private fun docxHtml(bytes: ByteArray) = DocxConverter.toHtml(ByteArrayInputStream(bytes))

    @Test
    fun docxBasicFormattingAndTurkishText() {
        val html = docxHtml(docx(
            "<w:p><w:r><w:t>Şişli İğne</w:t></w:r></w:p>" +
                "<w:p><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>kalın&lt;x&gt;</w:t></w:r></w:p>" +
                "<w:p><w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t>düz</w:t></w:r></w:p>",
        ))
        assertTrue(html.contains("<p>Şişli İğne</p>"))
        assertTrue(html.contains("<em><strong>kalın&lt;x&gt;</strong></em>"))
        assertTrue(html.contains("<p>düz</p>"))
    }

    @Test
    fun docxHeadingsViaStyles() {
        val styles = "<w:styles $ns><w:style w:styleId=\"H1\"><w:name w:val=\"heading 1\"/></w:style>" +
            "<w:style w:styleId=\"Custom\"><w:name w:val=\"Özel\"/><w:basedOn w:val=\"H1\"/></w:style></w:styles>"
        val html = docxHtml(docx(
            "<w:p><w:pPr><w:pStyle w:val=\"H1\"/></w:pPr><w:r><w:t>Başlık</w:t></w:r></w:p>" +
                "<w:p><w:pPr><w:pStyle w:val=\"Custom\"/></w:pPr><w:r><w:t>Türev</w:t></w:r></w:p>",
            "word/styles.xml" to styles,
        ))
        assertTrue(html.contains("<h1>Başlık</h1>"))
        assertTrue(html.contains("<h1>Türev</h1>"))
    }

    @Test
    fun docxListsNestedAndOrdered() {
        val numbering = "<w:numbering $ns><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl>" +
            "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"
        fun item(lvl: Int, t: String) =
            "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"$lvl\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>$t</w:t></w:r></w:p>"
        val html = docxHtml(docx(
            item(0, "a") + item(1, "b") + item(1, "c") + item(0, "d") + "<w:p><w:r><w:t>son</w:t></w:r></w:p>",
            "word/numbering.xml" to numbering,
        ))
        assertEquals("<ul><li>a<ol><li>b</li><li>c</li></ol></li><li>d</li></ul><p>son</p>", html)
    }

    @Test
    fun docxTableWithMergedCells() {
        val html = docxHtml(docx(
            "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p><w:r><w:t>üst</w:t></w:r></w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:tcPr><w:vMerge w:val=\"restart\"/></w:tcPr><w:p><w:r><w:t>sol</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>sağ1</w:t></w:r></w:p></w:tc></w:tr>" +
                "<w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:p><w:r><w:t>sağ2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>",
        ))
        assertTrue(html.contains("colspan=\"2\""))
        assertTrue(html.contains("rowspan=\"2\""))
        assertEquals(1, Regex("sol").findAll(html).count())
        assertTrue(html.contains("sağ2"))
    }

    @Test
    fun docxEmbeddedImageAndMissingImage() {
        val rels = "<Relationships><Relationship Id=\"rId1\" Type=\"x/image\" Target=\"media/a.png\"/></Relationships>"
        val drawing = { id: String -> "<w:p><w:r><w:drawing><a:blip xmlns:a=\"a\" r:embed=\"$id\"/></w:drawing></w:r></w:p>" }
        val html = docxHtml(docx(
            drawing("rId1") + drawing("rId9"),
            "word/_rels/document.xml.rels" to rels,
            "word/media/a.png" to png(),
        ))
        assertTrue(html.contains("src=\"data:image/png;base64,"))
        assertTrue(html.contains("[görsel gösterilemiyor]"))
    }

    @Test
    fun docxHyperlinkOnlySafeSchemes() {
        val rels = "<Relationships><Relationship Id=\"rId1\" Type=\"x/hyperlink\" Target=\"https://a.com/\" TargetMode=\"External\"/>" +
            "<Relationship Id=\"rId2\" Type=\"x/hyperlink\" Target=\"javascript:alert(1)\" TargetMode=\"External\"/></Relationships>"
        val html = docxHtml(docx(
            "<w:p><w:hyperlink r:id=\"rId1\"><w:r><w:t>iyi</w:t></w:r></w:hyperlink><w:hyperlink r:id=\"rId2\"><w:r><w:t>kötü</w:t></w:r></w:hyperlink></w:p>",
            "word/_rels/document.xml.rels" to rels,
        ))
        assertTrue(html.contains("<a href=\"https://a.com/\">iyi</a>"))
        assertFalse(html.contains("javascript"))
        assertTrue(html.contains("kötü"))
    }

    @Test
    fun docxEmptyDocument() {
        assertTrue(docxHtml(docx("")).contains("Belge boş"))
    }

    private fun docxError(bytes: ByteArray): String {
        try {
            docxHtml(bytes)
        } catch (e: OfficeFormatException) {
            return e.message!!
        }
        fail("hata bekleniyordu")
        return ""
    }

    @Test
    fun invalidInputsGiveClearErrors() {
        assertTrue(docxError(ByteArray(0)).contains("Geçerli"))
        assertTrue(docxError("düz metin dosyası".toByteArray()).contains("Geçerli"))
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertTrue(docxError(ole).contains("desteklenmiyor"))
        assertTrue(docxError(zip("foo.xml" to "<a/>")).contains("DOCX"))
        val whole = docx("<w:p><w:r><w:t>x</w:t></w:r></w:p>")
        assertTrue(docxError(whole.copyOf(40)).isNotEmpty())
        assertTrue(docxError(zip("word/document.xml" to "<w:document><w:body>")).isNotEmpty())
    }

    // --- XLSX ---

    private fun xlsx(sheetXml: String, shared: String? = null, styles: String? = null): ByteArray {
        val parts = ArrayList<Pair<String, Any>>()
        parts += "xl/workbook.xml" to "<workbook xmlns:r=\"r\"><sheets><sheet name=\"Veri &amp; Özet\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        parts += "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Type=\"x/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>"
        parts += "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$sheetXml</sheetData></worksheet>"
        if (shared != null) parts += "xl/sharedStrings.xml" to shared
        if (styles != null) parts += "xl/styles.xml" to styles
        return zip(*parts.toTypedArray())
    }

    private fun xlsxHtml(bytes: ByteArray) = XlsxConverter.toHtml(ByteArrayInputStream(bytes))

    @Test
    fun xlsxCellTypesAndSharedStrings() {
        val shared = "<sst><si><t>Ad</t></si><si><r><t>Şi</t></r><r><t>şe</t></r></si></sst>"
        val sheet = "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\"><v>0.30000000000000004</v></c><c r=\"C2\" t=\"b\"><v>1</v></c><c r=\"D2\" t=\"str\"><f>A1</f><v>formül</v></c></row>"
        val html = xlsxHtml(xlsx(sheet, shared))
        assertTrue(html.contains("<h2>Veri &amp; Özet</h2>"))
        assertTrue(html.contains("<td>Ad</td><td>Şişe</td>"))
        assertTrue(html.contains("<td>0.3</td><td></td><td>DOĞRU</td><td>formül</td>"))
    }

    @Test
    fun xlsxDatesAndPercents() {
        val styles = "<styleSheet><numFmts><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/><numFmt numFmtId=\"165\" formatCode=\"0.0&quot;d&quot;\"/></numFmts>" +
            "<cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/><xf numFmtId=\"164\"/><xf numFmtId=\"9\"/><xf numFmtId=\"165\"/></cellXfs></styleSheet>"
        val sheet = "<row r=\"1\"><c r=\"A1\" s=\"1\"><v>45000</v></c><c r=\"B1\" s=\"2\"><v>45000.5</v></c><c r=\"C1\" s=\"3\"><v>0.25</v></c>" +
            "<c r=\"D1\" s=\"4\"><v>3</v></c><c r=\"E1\" s=\"0\"><v>45000</v></c></row>"
        val html = xlsxHtml(xlsx(sheet, styles = styles))
        assertTrue(html.contains("<td>15.03.2023</td>"))
        assertTrue(html.contains("<td>15.03.2023 12:00</td>"))
        assertTrue(html.contains("<td>25%</td>"))
        assertTrue(html.contains("<td>3</td>"))
        assertTrue(html.contains("<td>45000</td>"))
    }

    @Test
    fun xlsxMergedCellsHiddenSheetsAndGaps() {
        val sheet = "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>geniş</t></is></c></row><row r=\"4\"><c r=\"B4\"><v>7</v></c></row>"
        val bytes = zip(
            "xl/workbook.xml" to "<workbook xmlns:r=\"r\"><sheets><sheet name=\"A\" r:id=\"rId1\"/><sheet name=\"Gizli\" state=\"hidden\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData>$sheet</sheetData><mergeCells><mergeCell ref=\"A1:C2\"/></mergeCells></worksheet>",
            "xl/worksheets/sheet2.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>",
        )
        val html = xlsxHtml(bytes)
        assertTrue(html.contains("colspan=\"3\" rowspan=\"2\""))
        assertFalse(html.contains("Gizli"))
        assertEquals(4, Regex("<tr>").findAll(html).count())
        assertTrue(html.contains("<td>7</td>"))
    }

    @Test
    fun xlsxEmptySheetAndRowLimit() {
        assertTrue(xlsxHtml(xlsx("")).contains("Bu sayfa boş"))
        val rows = (1..XlsxConverter.MAX_ROWS + 10).joinToString("") { "<row r=\"$it\"><c r=\"A$it\"><v>$it</v></c></row>" }
        val html = xlsxHtml(xlsx(rows))
        assertTrue(html.contains("Çok büyük sayfa"))
        assertFalse(html.contains("<td>${XlsxConverter.MAX_ROWS + 1}</td>"))
    }

    @Test
    fun xlsxHelpers() {
        assertEquals(1 to 27, XlsxConverter.parseRef("AB2"))
        assertNull(XlsxConverter.parseRef("A"))
        assertNull(XlsxConverter.parseRef("12"))
        assertTrue(XlsxConverter.looksLikeDateFormat("dd/mm/yyyy"))
        assertTrue(XlsxConverter.looksLikeDateFormat("[\$-409]h:mm AM/PM"))
        assertFalse(XlsxConverter.looksLikeDateFormat("0.00\"day\""))
        assertFalse(XlsxConverter.looksLikeDateFormat("General"))
        assertEquals("100", XlsxConverter.formatNumber("1E2"))
        assertEquals("0.3", XlsxConverter.formatNumber("0.30000000000000004"))
    }

    // --- PPTX ---

    private val pns = "xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\""

    private fun pptx(slide: String, notes: String? = null): ByteArray {
        val parts = ArrayList<Pair<String, Any>>()
        parts += "ppt/presentation.xml" to "<p:presentation $pns><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId1\"/></p:sldIdLst></p:presentation>"
        parts += "ppt/_rels/presentation.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Type=\"x/slide\" Target=\"slides/slide1.xml\"/></Relationships>"
        parts += "ppt/slides/slide1.xml" to "<p:sld $pns><p:cSld><p:spTree>$slide</p:spTree></p:cSld></p:sld>"
        val rels = StringBuilder("<Relationships><Relationship Id=\"rId5\" Type=\"x/image\" Target=\"../media/i.png\"/>")
        if (notes != null) {
            rels.append("<Relationship Id=\"rId6\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" Target=\"../notesSlides/notesSlide1.xml\"/>")
            parts += "ppt/notesSlides/notesSlide1.xml" to "<p:notes $pns><p:cSld><p:spTree>$notes</p:spTree></p:cSld></p:notes>"
        }
        rels.append("</Relationships>")
        parts += "ppt/slides/_rels/slide1.xml.rels" to rels.toString()
        parts += "ppt/media/i.png" to png()
        return zip(*parts.toTypedArray())
    }

    private fun shape(ph: String?, paras: String) =
        "<p:sp><p:nvSpPr><p:nvPr>${if (ph != null) "<p:ph type=\"$ph\"/>" else ""}</p:nvPr></p:nvSpPr><p:txBody>$paras</p:txBody></p:sp>"

    private fun para(t: String, lvl: Int? = null) =
        "<a:p><a:pPr${if (lvl != null) " lvl=\"$lvl\"" else ""}/><a:r><a:t>$t</a:t></a:r></a:p>"

    @Test
    fun pptxTitleBulletsAndNotes() {
        val slide = shape("title", para("Başlık Ğ")) +
            shape("body", para("bir") + para("alt", 1) + para("iki")) +
            shape(null, para("serbest metin")) +
            shape("sldNum", para("7"))
        val notes = shape("body", para("konuşma notu"))
        val html = PptxConverter.toHtml(ByteArrayInputStream(pptx(slide, notes)))
        assertTrue(html.contains("Slayt 1"))
        assertTrue(html.contains("<h2>Başlık Ğ</h2>"))
        assertTrue(html.contains("<ul><li>bir<ul><li>alt</li></ul></li><li>iki</li></ul>"))
        assertTrue(html.contains("<p>serbest metin</p>"))
        assertFalse(html.contains(">7<"))
        assertTrue(html.contains("konuşma notu"))
    }

    @Test
    fun pptxPictureTableAndEmptySlide() {
        val pic = "<p:pic><p:nvPicPr><p:cNvPr descr=\"resim\"/></p:nvPicPr><p:blipFill><a:blip r:embed=\"rId5\"/></p:blipFill></p:pic>"
        val tbl = "<p:graphicFrame><a:graphic><a:graphicData><a:tbl><a:tr><a:tc gridSpan=\"2\"><p:txBody><a:p><a:r><a:t>h</a:t></a:r></a:p></p:txBody></a:tc>" +
            "<a:tc hMerge=\"1\"><p:txBody><a:p/></p:txBody></a:tc></a:tr></a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        val html = PptxConverter.toHtml(ByteArrayInputStream(pptx(pic + tbl)))
        assertTrue(html.contains("data:image/png;base64,"))
        assertTrue(html.contains("<td colspan=\"2\">h</td>"))
        assertTrue(PptxConverter.toHtml(ByteArrayInputStream(pptx(""))).contains("Metin yok"))
    }

    @Test
    fun wrongTypeGivesClearError() {
        try {
            PptxConverter.toHtml(ByteArrayInputStream(docx("")))
            fail()
        } catch (e: OfficeFormatException) {
            assertTrue(e.message!!.contains("PPTX"))
        }
    }

    @Test
    fun hugeXmlEntryIsSkippedNotLoaded() {
        val big = ByteArray(70 * 1024 * 1024) { ' '.code.toByte() }
        assertTrue(docxError(zip("word/document.xml" to big)).isNotEmpty())
    }
}
