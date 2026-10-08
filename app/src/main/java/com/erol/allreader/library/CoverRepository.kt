package com.erol.allreader.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.erol.allreader.data.BookEntity
import com.erol.allreader.engine.Engine
import com.erol.allreader.engine.page.MuPdfDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sayfa motoru dosyaları için kapak küçük resimleri; `cacheDir` altında diskte saklanır. */
class CoverRepository(private val context: Context) {
    private val dir = File(context.cacheDir, "covers").apply { mkdirs() }

    /** Kapak yoksa veya üretilemiyorsa null (arayüz simgeye düşer). */
    suspend fun load(book: BookEntity): Bitmap? {
        val format = book.documentFormat ?: return null
        if (format.engine != Engine.PAGE) return null

        val key = "${book.id}_${book.modifiedAt}_${book.sizeBytes}"
        val image = File(dir, "$key.jpg")
        val none = File(dir, "$key.none")
        return withContext(Dispatchers.IO) {
            if (none.exists()) return@withContext null
            if (!image.exists()) {
                val bitmap = MuPdfDocument.renderCover(context, Uri.parse(book.uri), format, book.name, COVER_WIDTH)
                if (bitmap == null) {
                    runCatching { none.createNewFile() }
                    return@withContext null
                }
                runCatching {
                    image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                }.onFailure { image.delete() }
                bitmap.recycle()
            }
            BitmapFactory.decodeFile(image.absolutePath)
        }
    }

    private companion object {
        const val COVER_WIDTH = 240
    }
}
