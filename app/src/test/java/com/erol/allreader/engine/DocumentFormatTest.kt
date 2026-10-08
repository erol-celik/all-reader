package com.erol.allreader.engine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentFormatTest {
    @Test
    fun mapsExtensionsToFormats() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromFileName("kitap.pdf"))
        assertEquals(DocumentFormat.IMAGE, DocumentFormat.fromFileName("foto.JPEG"))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormat.fromFileName("not.markdown"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.fromFileName("rapor.docx"))
    }

    @Test
    fun extensionIsCaseInsensitive() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromFileName("BÜYÜK DOSYA.PDF"))
        assertEquals(DocumentFormat.XLSX, DocumentFormat.fromFileName("Tablo.XlSx"))
    }

    @Test
    fun turkishDefaultLocaleDoesNotBreakDottedAndDotlessI() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(DocumentFormat.IMAGE, DocumentFormat.fromFileName("FOTO.GIF"))
            assertEquals(DocumentFormat.PPTX, DocumentFormat.fromFileName("SUNUM.PPTX"))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun unsupportedOrMissingExtensionReturnsNull() {
        assertNull(DocumentFormat.fromFileName("README"))
        assertNull(DocumentFormat.fromFileName("eski.doc"))
        assertNull(DocumentFormat.fromFileName("eski.xls"))
        assertNull(DocumentFormat.fromFileName("bitis."))
        assertNull(DocumentFormat.fromFileName(""))
    }

    @Test
    fun onlyLastExtensionCounts() {
        assertEquals(DocumentFormat.TXT, DocumentFormat.fromFileName("arsiv.pdf.txt"))
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromFileName("a.b.c.pdf"))
    }

    @Test
    fun enginesMatchPlan() {
        assertEquals(Engine.PAGE, DocumentFormat.PDF.engine)
        assertEquals(Engine.PAGE, DocumentFormat.CBZ.engine)
        assertEquals(Engine.REFLOW, DocumentFormat.DOCX.engine)
        assertEquals(Engine.REFLOW, DocumentFormat.TXT.engine)
    }

    @Test
    fun mapsMimeTypesAsFallback() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.fromMime("application/pdf"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.fromMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(DocumentFormat.TXT, DocumentFormat.fromMime("TEXT/PLAIN"))
        assertNull(DocumentFormat.fromMime("application/zip"))
        assertNull(DocumentFormat.fromMime(null))
    }
}
