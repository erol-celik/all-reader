package com.erol.allreader.engine

/** Dosyanın hangi okuma motoruna gideceği. */
enum class Engine { PAGE, REFLOW }

enum class DocumentFormat(val engine: Engine, val label: String, vararg val extensions: String) {
    PDF(Engine.PAGE, "PDF", "pdf"),
    CBZ(Engine.PAGE, "CBZ", "cbz"),
    IMAGE(Engine.PAGE, "Görsel", "jpg", "jpeg", "png", "webp", "gif", "bmp"),
    TXT(Engine.REFLOW, "TXT", "txt"),
    MARKDOWN(Engine.REFLOW, "MD", "md", "markdown"),
    DOCX(Engine.REFLOW, "DOCX", "docx"),
    XLSX(Engine.REFLOW, "XLSX", "xlsx"),
    PPTX(Engine.REFLOW, "PPTX", "pptx");

    companion object {
        /** Uzantıya göre format; uzantısız veya desteklenmeyen dosya için null. */
        fun fromFileName(name: String): DocumentFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return entries.firstOrNull { ext in it.extensions }
        }

        /** MIME türüne göre format (dosya adı yoksa/uzantısızsa yedek). */
        fun fromMime(mime: String?): DocumentFormat? = when (mime?.lowercase()) {
            "application/pdf" -> PDF
            "application/vnd.comicbook+zip", "application/x-cbz" -> CBZ
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp" -> IMAGE
            "text/plain" -> TXT
            "text/markdown", "text/x-markdown" -> MARKDOWN
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX
            else -> null
        }

        fun fromName(name: String): DocumentFormat? = entries.firstOrNull { it.name == name }
    }
}
