package com.erol.allreader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.erol.allreader.engine.DocumentFormat

@Entity(
    tableName = "books",
    indices = [Index(value = ["uri"], unique = true), Index(value = ["rootUri"])],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Dosyanın SAF belge URI'si. */
    val uri: String,
    /** Dosyanın bulunduğu, kalıcı izin verilmiş klasör (tree) URI'si. */
    val rootUri: String,
    val name: String,
    /** [DocumentFormat.name] değeri. */
    val format: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long? = null,
    /** 0..1 arası genel ilerleme. */
    val progress: Float = 0f,
    /** Sayfa motoru için son sayfa. */
    val lastPage: Int = 0,
    /** Akış motoru için kaydırma yüzdesi (0..1). */
    val lastOffset: Float = 0f,
) {
    val documentFormat: DocumentFormat? get() = DocumentFormat.fromName(format)
}
