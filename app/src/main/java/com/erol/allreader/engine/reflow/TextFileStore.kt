package com.erol.allreader.engine.reflow

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoadedText(
    /** `\n` ile normalize edilmiş metin. */
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    /** Okuma anındaki dosya değişiklik zamanı; kaydederken çakışmayı yakalamak için. */
    val lastModified: Long?,
    val sizeBytes: Long,
)

sealed interface SaveResult {
    class Saved(val loaded: LoadedText) : SaveResult
    /** Dosya, okunduktan sonra başka bir yerde değişti. */
    data object Conflict : SaveResult
    /** Metin dosyanın mevcut kodlamasında ifade edilemiyor (örn. Windows-1254'e emoji). */
    class NotEncodable(val charsetName: String) : SaveResult
    class Failed(val message: String) : SaveResult
}

class Draft(val text: String, val baseModified: Long?)

/** TXT/MD dosyalarını okur ve güvenli yazar (yedek, çakışma kontrolü, doğrulama); taslakları saklar. */
class TextFileStore(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val backupDir = File(context.cacheDir, "backups").apply { mkdirs() }
    private val draftDir = File(context.cacheDir, "drafts").apply { mkdirs() }

    suspend fun read(uri: Uri): LoadedText = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited(MAX_READ_BYTES) }
            ?: throw IOException("Dosya açılamadı")
        val decoded = TextCodec.decode(bytes)
        LoadedText(decoded.text, decoded.encoding, decoded.lineEnding, queryLastModified(uri), bytes.size.toLong())
    }

    suspend fun save(
        uri: Uri,
        bookId: Long,
        text: String,
        base: LoadedText,
        overwriteConflict: Boolean,
        allowUtf8Fallback: Boolean,
    ): SaveResult = withContext(Dispatchers.IO) {
        try {
            val current = queryLastModified(uri)
            if (!overwriteConflict && current != null && base.lastModified != null && current != base.lastModified) {
                return@withContext SaveResult.Conflict
            }

            var encoding = base.encoding
            var bytes = TextCodec.encode(text, encoding, base.lineEnding)
            if (bytes == null) {
                if (!allowUtf8Fallback) return@withContext SaveResult.NotEncodable(encoding.charsetName)
                encoding = TextEncoding.UTF8
                bytes = TextCodec.encode(text, encoding, base.lineEnding)
                    ?: return@withContext SaveResult.Failed("Metinde kodlanamayan karakter var")
            }

            val backup = File(backupDir, "$bookId.bak")
            val hadBackup = runCatching {
                resolver.openInputStream(uri)?.use { input -> backup.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)

            try {
                val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Dosya yazmaya açılamadı")
                out.use {
                    it.write(bytes)
                    it.flush()
                }
                val written = querySize(uri)
                if (written != null && written != bytes.size.toLong()) throw IOException("Yazma doğrulanamadı")
            } catch (e: Exception) {
                if (hadBackup) restore(uri, backup)
                return@withContext SaveResult.Failed(e.message ?: e.javaClass.simpleName)
            }

            SaveResult.Saved(
                LoadedText(text, encoding, base.lineEnding, queryLastModified(uri), bytes.size.toLong()),
            )
        } catch (e: SecurityException) {
            SaveResult.Failed("Yazma izni yok")
        }
    }

    fun queryLastModified(uri: Uri): Long? = queryLong(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    private fun querySize(uri: Uri): Long? = queryLong(uri, DocumentsContract.Document.COLUMN_SIZE)

    private fun queryLong(uri: Uri, column: String): Long? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun restore(uri: Uri, backup: File) {
        runCatching {
            resolver.openOutputStream(uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } }
        }
    }

    // --- Taslaklar: kaydedilmemiş düzenlemeler çökme/süreç ölümünde kaybolmasın ---

    suspend fun saveDraft(bookId: Long, text: String, baseModified: Long?) = withContext(Dispatchers.IO) {
        runCatching {
            File(draftDir, "$bookId.txt").writeText(text, Charsets.UTF_8)
            File(draftDir, "$bookId.meta").writeText(baseModified?.toString() ?: "", Charsets.UTF_8)
        }
    }

    suspend fun loadDraft(bookId: Long): Draft? = withContext(Dispatchers.IO) {
        val textFile = File(draftDir, "$bookId.txt")
        if (!textFile.exists()) return@withContext null
        runCatching {
            val meta = File(draftDir, "$bookId.meta").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            Draft(textFile.readText(Charsets.UTF_8), meta)
        }.getOrNull()
    }

    suspend fun clearDraft(bookId: Long) = withContext(Dispatchers.IO) {
        File(draftDir, "$bookId.txt").delete()
        File(draftDir, "$bookId.meta").delete()
    }

    companion object {
        const val MAX_READ_BYTES = 20 * 1024 * 1024
        const val MAX_EDIT_BYTES = 2 * 1024 * 1024
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > max) throw IOException("Dosya çok büyük (en fazla ${max / (1024 * 1024)} MB)")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
