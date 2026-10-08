package com.erol.allreader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Yer imi. Sayfa motorunda [page] ≥ 0 (sayfa indeksi); akış motorunda [page] = -1 ve konum [ratio] (0..1). */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val ratio: Float,
    val label: String,
    val createdAt: Long,
) {
    val isPage: Boolean get() = page >= 0
}
