package com.erol.allreader.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.erol.allreader.engine.DocumentFormat
import androidx.room.withTransaction
import com.erol.allreader.data.AppDatabase
import com.erol.allreader.data.BookEntity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanResult(
    val folderCount: Int,
    val added: Int,
    val removed: Int,
    val failedFolders: Int,
)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
) {
    private val dao = db.bookDao()
    private val scanner = FolderScanner(context.contentResolver)
    private val scanMutex = Mutex()

    val books = dao.observeAll()
    val recent = dao.observeRecent()

    /** Kalıcı okuma izni verilmiş klasörler; izin geri alınmışsa listede görünmez. */
    fun folders(): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    /**
     * Klasörü kalıcı izinle kaydeder. Düzenleme için yazma izni de alınır; sağlayıcı vermezse
     * yalnızca okuma izniyle devam edilir.
     */
    fun persistFolder(uri: Uri) {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // İzin kalıcı yapılamadı; klasör kalıcı izin listesine girmez, tarama onu görmez.
            }
        }
    }

    /** Bu klasör için kalıcı yazma izni var mı? */
    fun hasWriteAccess(rootUri: String): Boolean = context.contentResolver.persistedUriPermissions
        .any { it.isWritePermission && it.uri.toString() == rootUri }

    suspend fun get(id: Long): BookEntity? = dao.getById(id)

    /**
     * Başka bir uygulamadan ("Birlikte aç") gelen dosyayı kütüphaneye [EXTERNAL_ROOT] köküyle ekler
     * (yeniden taramalar bu kayıtlara dokunmaz). Desteklenmeyen dosyada null döner.
     */
    suspend fun openExternal(uri: Uri, mimeType: String?): Long? = withContext(Dispatchers.IO) {
        dao.getAll().firstOrNull { it.uri == uri.toString() }?.let {
            dao.markOpened(it.id, System.currentTimeMillis())
            return@withContext it.id
        }
        var name: String? = null
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        } catch (_: Exception) {
            // Ad alınamazsa URI'den ve MIME'dan tahmin edilir.
        }
        val fileName = name ?: uri.lastPathSegment ?: "belge"
        val format = DocumentFormat.fromFileName(fileName) ?: DocumentFormat.fromMime(mimeType) ?: return@withContext null
        // Okunamayan dosya (izin yok, silinmiş) kütüphaneye kayıt olarak girmesin.
        try {
            context.contentResolver.openInputStream(uri)?.close() ?: return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Çoğu sağlayıcı kalıcı izin vermez; izin etkinlik yaşadığı sürece geçerlidir.
        }
        dao.insert(
            BookEntity(
                uri = uri.toString(),
                rootUri = EXTERNAL_ROOT,
                name = fileName,
                format = format.name,
                sizeBytes = size,
                modifiedAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateFileInfo(id: Long, size: Long, modified: Long) = dao.updateFileInfo(id, size, modified)

    suspend fun markOpened(id: Long) = dao.markOpened(id, System.currentTimeMillis())

    suspend fun saveProgress(id: Long, progress: Float, page: Int, offset: Float = 0f) =
        dao.saveProgress(id, progress.coerceIn(0f, 1f), page.coerceAtLeast(0), offset.coerceIn(0f, 1f))

    /**
     * Klasörleri yeniden tarar ve veritabanını eşitler. Eşzamanlı çağrılar sıraya girer.
     * Okunamayan bir klasörün (örn. çıkarılmış SD kart) kayıtları silinmez.
     */
    suspend fun rescan(): ScanResult = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val roots = folders()
            val scanned = LinkedHashMap<String, Pair<Uri, ScannedFile>>()
            val failed = mutableSetOf<String>()

            for (root in roots) {
                try {
                    for (file in scanner.scan(root)) scanned.putIfAbsent(file.key, root to file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += root.toString()
                }
            }

            var added = 0
            var removed = 0
            db.withTransaction {
                val existing = dao.getAll().filter { it.rootUri != EXTERNAL_ROOT }
                val existingByKey = HashMap<String, BookEntity>()
                val toDelete = mutableListOf<Long>()
                for (book in existing) {
                    val key = runCatching { fileKey(Uri.parse(book.uri)) }.getOrNull()
                    if (key == null || existingByKey.putIfAbsent(key, book) != null) toDelete += book.id
                }

                val toInsert = mutableListOf<BookEntity>()
                val toUpdate = mutableListOf<BookEntity>()
                for ((key, rootAndFile) in scanned) {
                    val (root, file) = rootAndFile
                    val old = existingByKey[key]
                    if (old == null) {
                        toInsert += BookEntity(
                            uri = file.uri.toString(),
                            rootUri = root.toString(),
                            name = file.name,
                            format = file.format.name,
                            sizeBytes = file.sizeBytes,
                            modifiedAt = file.modifiedAt,
                        )
                    } else {
                        val updated = old.copy(
                            uri = file.uri.toString(),
                            rootUri = root.toString(),
                            name = file.name,
                            format = file.format.name,
                            sizeBytes = file.sizeBytes,
                            modifiedAt = file.modifiedAt,
                        )
                        if (updated != old) toUpdate += updated
                    }
                }
                for ((key, book) in existingByKey) {
                    if (key !in scanned && book.rootUri !in failed) toDelete += book.id
                }

                // "Birlikte aç" kayıtları: erişim izni gittiyse ya da dosya silindiyse kütüphaneden düşer.
                dao.getAll().filter { it.rootUri == EXTERNAL_ROOT && !isReadable(Uri.parse(it.uri)) }
                    .forEach { toDelete += it.id }

                toDelete.chunked(SQL_CHUNK).forEach { dao.deleteByIds(it) }
                if (toUpdate.isNotEmpty()) dao.updateAll(toUpdate)
                if (toInsert.isNotEmpty()) dao.insertAll(toInsert)
                added = toInsert.size
                removed = toDelete.size
            }
            ScanResult(roots.size, added, removed, failed.size)
        }
    }

    private fun isReadable(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    companion object {
        /** "Birlikte aç" ile gelen, bir kütüphane klasörüne ait olmayan dosyaların kökü. */
        const val EXTERNAL_ROOT = "external"
        private const val SQL_CHUNK = 500
    }
}
