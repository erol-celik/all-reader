package com.erol.allreader.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.erol.allreader.engine.DocumentFormat
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class ScannedFile(
    val uri: Uri,
    val name: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    /** Aynı dosyaya farklı klasör (tree) izinleriyle ulaşılsa da aynı kalan anahtar. */
    val key: String get() = fileKey(uri)
}

fun fileKey(uri: Uri): String = "${uri.authority}|${DocumentsContract.getDocumentId(uri)}"

/**
 * SAF klasör ağacını tarar. `DocumentFile` her alan için ayrı sorgu yaptığı için yavaş olduğundan
 * doğrudan `DocumentsContract` ile tek sorguda tüm çocukları alır.
 */
class FolderScanner(private val resolver: ContentResolver) {

    /** Kök klasör okunamazsa (izin gitti, kart çıkarıldı) istisna fırlatır; alt klasör hataları atlanır. */
    suspend fun scan(root: Uri): List<ScannedFile> {
        val result = mutableListOf<ScannedFile>()
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val visited = hashSetOf(rootId)
        val pending = ArrayDeque<String>().apply { add(rootId) }

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val dirId = pending.removeLast()
            try {
                listChildren(root, dirId, result, pending, visited)
            } catch (e: Exception) {
                if (dirId == rootId) throw e
            }
        }
        return result
    }

    private fun listChildren(
        root: Uri,
        dirId: String,
        files: MutableList<ScannedFile>,
        pending: ArrayDeque<String>,
        visited: MutableSet<String>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, dirId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw IOException("Klasör okunamadı: $dirId")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".") && visited.add(id)) pending.add(id)
                    continue
                }
                val format = DocumentFormat.fromFileName(name) ?: continue
                files += ScannedFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(root, id),
                    name = name,
                    format = format,
                    sizeBytes = it.getLong(3),
                    modifiedAt = it.getLong(4),
                )
            }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
