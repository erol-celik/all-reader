package com.erol.allreader

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.erol.allreader.data.AppDatabase
import com.erol.allreader.data.BookmarkRepository
import com.erol.allreader.data.SettingsRepository
import com.erol.allreader.engine.reflow.TextFileStore
import com.erol.allreader.library.CoverRepository
import com.erol.allreader.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Elle bağımlılık sağlayıcı (Hilt yok). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    private val db: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "allreader.db").build()
    }
    val bookmarks: BookmarkRepository by lazy { BookmarkRepository(db) }
    val library: LibraryRepository by lazy { LibraryRepository(context, db) }
    val covers: CoverRepository by lazy { CoverRepository(context) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val textFiles: TextFileStore by lazy { TextFileStore(context) }

    /** Ekran kapansa da bitmesi gereken işler (ilerleme kaydı, belge kapatma) için. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class AllReaderApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
