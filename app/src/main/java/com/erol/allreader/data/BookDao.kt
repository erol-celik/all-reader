package com.erol.allreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Insert
    suspend fun insertAll(books: List<BookEntity>)

    @Update
    suspend fun updateAll(books: List<BookEntity>)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    @Query("UPDATE books SET sizeBytes = :size, modifiedAt = :modified WHERE id = :id")
    suspend fun updateFileInfo(id: Long, size: Long, modified: Long)

    @Query("UPDATE books SET progress = :progress, lastPage = :page, lastOffset = :offset WHERE id = :id")
    suspend fun saveProgress(id: Long, progress: Float, page: Int, offset: Float)
}
