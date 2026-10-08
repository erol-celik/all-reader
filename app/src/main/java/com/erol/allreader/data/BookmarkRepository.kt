package com.erol.allreader.data

import kotlin.math.abs
import kotlinx.coroutines.flow.Flow

class BookmarkRepository(db: AppDatabase) {
    private val dao = db.bookmarkDao()

    fun observe(bookId: Long): Flow<List<BookmarkEntity>> = dao.observe(bookId)

    /** Sayfa [page] için yer imi varsa kaldırır, yoksa ekler. Sonuç: eklendiyse true. */
    suspend fun togglePage(bookId: Long, page: Int): Boolean {
        val existing = dao.getAll(bookId).filter { it.page == page }
        if (existing.isNotEmpty()) {
            dao.deleteAll(existing.map { it.id })
            return false
        }
        dao.insert(BookmarkEntity(bookId = bookId, page = page, ratio = 0f, label = "Sayfa ${page + 1}", createdAt = System.currentTimeMillis()))
        return true
    }

    /** Akış motoru: [ratio] civarında (±[REFLOW_TOLERANCE]) yer imi varsa kaldırır, yoksa ekler. */
    suspend fun toggleRatio(bookId: Long, ratio: Float): Boolean {
        val existing = dao.getAll(bookId).filter { !it.isPage && abs(it.ratio - ratio) <= REFLOW_TOLERANCE }
        if (existing.isNotEmpty()) {
            dao.deleteAll(existing.map { it.id })
            return false
        }
        val pct = (ratio * 100).toInt().coerceIn(0, 100)
        dao.insert(BookmarkEntity(bookId = bookId, page = -1, ratio = ratio, label = "%$pct konumunda", createdAt = System.currentTimeMillis()))
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)

    companion object {
        const val REFLOW_TOLERANCE = 0.01f
    }
}
