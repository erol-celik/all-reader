package com.erol.allreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.erol.allreader.AllReaderApp
import com.erol.allreader.data.BookEntity
import com.erol.allreader.library.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val recent: List<BookEntity> = emptyList(),
    val query: String = "",
    val scanning: Boolean = false,
    val folderCount: Int = 0,
    val loaded: Boolean = false,
)

class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val scanning = MutableStateFlow(false)
    private val folderCount = MutableStateFlow(repo.folders().size)

    private val _messages = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _messages

    val state: StateFlow<LibraryUiState> = combine(
        repo.books, repo.recent, query, scanning, folderCount,
    ) { books, recent, q, isScanning, folders ->
        val filtered = if (q.isBlank()) books else books.filter { it.name.contains(q.trim(), ignoreCase = true) }
        LibraryUiState(filtered, recent, q, isScanning, folders, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        rescan()
    }

    fun setQuery(value: String) = query.update { value }

    fun consumeMessage() = _messages.update { null }

    fun rescan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            try {
                folderCount.value = repo.folders().size
                val result = repo.rescan()
                folderCount.value = result.folderCount
                _messages.value = buildMessage(result.added, result.removed, result.failedFolders)
            } catch (e: Exception) {
                _messages.value = "Tarama başarısız: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                scanning.value = false
            }
        }
    }

    /** Seçilen klasörü kalıcı izinle kaydedip tarar. */
    fun addFolder(uri: android.net.Uri) {
        repo.persistFolder(uri)
        rescan()
    }

    fun onOpen(book: BookEntity) {
        viewModelScope.launch { repo.markOpened(book.id) }
    }

    private fun buildMessage(added: Int, removed: Int, failed: Int): String? {
        val parts = buildList {
            if (added > 0) add("$added yeni dosya eklendi")
            if (removed > 0) add("$removed dosya kaldırıldı")
            if (failed > 0) add("$failed klasör okunamadı")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel((this[APPLICATION_KEY] as AllReaderApp).container.library) }
        }
    }
}
