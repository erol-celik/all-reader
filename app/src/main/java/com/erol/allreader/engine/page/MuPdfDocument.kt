package com.erol.allreader.engine.page

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.erol.allreader.engine.DocumentFormat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** MuPDF thread-safe değildir: tüm belge işlemleri bu tek iş parçacığında yapılır. */
internal val mupdfDispatcher: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "mupdf").apply { isDaemon = true } }.asCoroutineDispatcher()

/** Sayfa boyutuna oranlanmış (0..1) dikdörtgen; üst-sol köşe orijin. */
data class FractionRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

sealed interface OpenResult {
    class Opened(val document: MuPdfDocument) : OpenResult
    /** Şifreli belge; [PendingDocument.authenticate] ile açılır. */
    class NeedsPassword(val pending: PendingDocument) : OpenResult
    class Failed(val message: String) : OpenResult
}

/** Parolası beklenen belge. Kullanılmayacaksa [close] çağrılmalı. */
class PendingDocument internal constructor(
    private val doc: Document,
    private val source: Source,
) {
    /** Doğru parolada belgeyi döndürür, yanlışsa null. */
    suspend fun authenticate(password: String): MuPdfDocument? = withContext(mupdfDispatcher) {
        if (doc.authenticatePassword(password)) MuPdfDocument.fromAuthenticated(doc, source) else null
    }

    suspend fun close() = withContext(mupdfDispatcher) {
        runCatching { doc.destroy() }
        source.close()
    }
}

/** Belgenin dayandığı kaynak: açık dosya tanıtıcısı veya geçici kopya. */
class Source internal constructor(private val pfd: ParcelFileDescriptor?, private val tempFile: File?) {
    fun close() {
        runCatching { pfd?.close() }
        runCatching { tempFile?.delete() }
    }
}

/** Sayfa motoru: PDF, CBZ ve görseller. Tüm metotlar kendi iş parçacığında çalışır. */
class MuPdfDocument private constructor(
    private val doc: Document,
    private val source: Source,
    val pageCount: Int,
    /** Her sayfanın yükseklik/genişlik oranı. */
    val aspects: FloatArray,
    /** Sayfa 0'ın nokta cinsinden genişliği (önizleme ölçeği için). */
    val firstPageWidth: Float,
) {
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(mupdfDispatcher) {
        val page = doc.loadPage(index)
        try {
            val bounds = page.bounds
            val pageW = (bounds.x1 - bounds.x0).coerceAtLeast(1f)
            val (w, _) = PageLayoutMath.renderSize(widthPx, aspects[index])
            AndroidDrawDevice.drawPage(page, Matrix.Scale(w / pageW))
        } finally {
            page.destroy()
        }
    }

    /**
     * Sayfadaki eşleşmeler (büyük/küçük harf duyarsız). Her eşleşme, sayfa boyutuna oranlanmış (0..1)
     * dikdörtgenlerden oluşur (satır sonunda bölünen eşleşme birden çok dikdörtgen verir).
     * Metin katmanı olmayan (taranmış) sayfalar boş döner.
     */
    suspend fun searchPage(index: Int, needle: String): List<List<FractionRect>> = withContext(mupdfDispatcher) {
        if (needle.isEmpty() || index !in 0 until pageCount) return@withContext emptyList()
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val w = (b.x1 - b.x0).coerceAtLeast(1f)
            val h = (b.y1 - b.y0).coerceAtLeast(1f)
            val hits = page.search(needle) ?: return@withContext emptyList()
            hits.map { quads ->
                quads.map { q ->
                    val xs = floatArrayOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                    val ys = floatArrayOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                    FractionRect(
                        left = ((xs.min() - b.x0) / w).coerceIn(0f, 1f),
                        top = ((ys.min() - b.y0) / h).coerceIn(0f, 1f),
                        right = ((xs.max() - b.x0) / w).coerceIn(0f, 1f),
                        bottom = ((ys.max() - b.y0) / h).coerceIn(0f, 1f),
                    )
                }
            }.filter { it.isNotEmpty() }
        } finally {
            page.destroy()
        }
    }

    suspend fun close() = withContext(mupdfDispatcher) {
        runCatching { doc.destroy() }
        source.close()
        // MuPDF'in iç önbelleği (store) belge kapanınca da tutulur; okuyucudan çıkınca belleği geri ver.
        runCatching { com.artifex.mupdf.fitz.Context.emptyStore() }
    }

    companion object {
        suspend fun open(context: Context, uri: Uri, format: DocumentFormat, displayName: String): OpenResult =
            withContext(mupdfDispatcher) {
                var source: Source? = null
                try {
                    val magic = magicFor(format, displayName)
                    val opened = openSource(context, uri, magic)
                    source = opened.second
                    val doc = opened.first
                    if (doc.needsPassword()) return@withContext OpenResult.NeedsPassword(PendingDocument(doc, source))
                    OpenResult.Opened(fromAuthenticated(doc, source))
                } catch (e: Throwable) {
                    source?.close()
                    OpenResult.Failed(describe(e))
                }
            }

        /**
         * Sadece ilk sayfayı çizer (kütüphane kapağı). Tüm sayfa boyutlarını taramadığı için büyük
         * belgelerde hızlıdır. Şifreli/bozuk belgede null döner.
         */
        suspend fun renderCover(
            context: Context,
            uri: Uri,
            format: DocumentFormat,
            displayName: String,
            widthPx: Int,
        ): Bitmap? = withContext(mupdfDispatcher) {
            var source: Source? = null
            var doc: Document? = null
            try {
                val opened = openSource(context, uri, magicFor(format, displayName))
                doc = opened.first
                source = opened.second
                if (doc.needsPassword() || doc.countPages() <= 0) return@withContext null
                val page = doc.loadPage(0)
                try {
                    val b = page.bounds
                    val pageW = (b.x1 - b.x0).coerceAtLeast(1f)
                    AndroidDrawDevice.drawPage(page, Matrix.Scale(widthPx / pageW))
                } finally {
                    page.destroy()
                }
            } catch (e: Throwable) {
                null
            } finally {
                runCatching { doc?.destroy() }
                source?.close()
            }
        }

        internal fun fromAuthenticated(doc: Document, source: Source): MuPdfDocument {
            val count = doc.countPages()
            if (count <= 0) {
                doc.destroy()
                source.close()
                throw IOException("Belgede sayfa yok")
            }
            val aspects = FloatArray(count)
            var firstW = 1f
            for (i in 0 until count) {
                val page = doc.loadPage(i)
                try {
                    val b = page.bounds
                    val w = (b.x1 - b.x0).coerceAtLeast(1f)
                    val h = (b.y1 - b.y0).coerceAtLeast(1f)
                    aspects[i] = h / w
                    if (i == 0) firstW = w
                } finally {
                    page.destroy()
                }
            }
            return MuPdfDocument(doc, source, count, aspects, firstW)
        }

        private fun magicFor(format: DocumentFormat, name: String): String = when (format) {
            DocumentFormat.PDF -> "file.pdf"
            DocumentFormat.CBZ -> "file.cbz"
            else -> "file." + name.substringAfterLast('.', "jpg").lowercase()
        }

        private fun openSource(context: Context, uri: Uri, magic: String): Pair<Document, Source> {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Dosya açılamadı")
            try {
                val stream = FdStream(pfd)
                stream.verifySeekable()
                return Document.openDocument(stream, magic) to Source(pfd, null)
            } catch (e: IOException) {
                // Aranamayan kaynak (örn. bulut sağlayıcı): geçici kopyaya düş.
                runCatching { pfd.close() }
                val temp = File.createTempFile("open", "." + magic.substringAfterLast('.'), context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } ?: throw IOException("Dosya açılamadı")
                    return Document.openDocument(temp.absolutePath) to Source(null, temp)
                } catch (t: Throwable) {
                    temp.delete()
                    throw t
                }
            }
        }

        private fun describe(e: Throwable): String = when {
            e is OutOfMemoryError -> "Dosya çok büyük, bellek yetmedi"
            e is SecurityException -> "Dosyaya erişim izni yok"
            e.message.isNullOrBlank() -> "Dosya açılamadı: bozuk veya desteklenmeyen dosya"
            else -> "Dosya açılamadı: bozuk veya desteklenmeyen dosya (${e.message})"
        }
    }
}

/** Dosya tanıtıcısı üzerinde MuPDF'in beklediği aranabilir akış. */
private class FdStream(pfd: ParcelFileDescriptor) : SeekableInputStream {
    private val channel: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    fun verifySeekable() {
        channel.position(0) // aranamayan kaynakta IOException fırlatır
    }

    override fun read(buffer: ByteArray): Int = channel.read(ByteBuffer.wrap(buffer))

    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            0 -> offset
            1 -> channel.position() + offset
            else -> channel.size() + offset
        }
        channel.position(target.coerceAtLeast(0))
        return channel.position()
    }

    override fun position(): Long = channel.position()
}
