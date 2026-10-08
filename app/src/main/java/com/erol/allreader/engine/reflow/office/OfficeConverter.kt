package com.erol.allreader.engine.reflow.office

import com.erol.allreader.engine.DocumentFormat
import java.io.InputStream

/** DOCX/XLSX/PPTX akışını biçimine göre HTML gövdesine çevirir. */
object OfficeConverter {
    fun toHtml(format: DocumentFormat, input: InputStream): String = when (format) {
        DocumentFormat.DOCX -> DocxConverter.toHtml(input)
        DocumentFormat.XLSX -> XlsxConverter.toHtml(input)
        DocumentFormat.PPTX -> PptxConverter.toHtml(input)
        else -> throw OfficeFormatException("Desteklenmeyen biçim")
    }
}
