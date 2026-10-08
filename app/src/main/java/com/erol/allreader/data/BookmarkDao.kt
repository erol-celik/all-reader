package com.erol.allreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    /** Sayfa yer imleri sayfa sırasıyla, akış yer imleri konuma göre. */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY page, ratio")
    fun observe(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    suspend fun getAll(bookId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)
}
