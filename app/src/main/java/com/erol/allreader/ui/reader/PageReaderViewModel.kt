package com.erol.allreader.ui.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erol.allreader.AppContainer
import com.erol.allreader.data.BookEntity
import com.erol.allreader.engine.page.MuPdfDocument
import com.erol.allreader.engine.page.OpenResult
import com.erol.allreader.engine.page.PendingDocument
import com.erol.allreader.data.BookmarkEntity
import com.erol.allreader.engine.page.FractionRect
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PageReaderState {
    data object Loading : PageReaderState
    data class NeedsPassword(val wrongPassword: Boolean) : PageReaderState
    data class Failed(val message: String) : PageReaderState
    class Ready(val book: BookEntity, val document: MuPdfDocument) : PageReaderState
}

/** Bir sayfadaki tek bir arama eşleşmesi. */
class PageHit(val page: Int, val rects: List<FractionRect>)

class PageSearchState(
    val query: String = "",
    val hits: List<PageHit> = emptyList(),
    /** [hits] içindeki etkin eşleşmenin sırası; eşleşme yoksa -1. */
    val current: Int = -1,
    val searching: Boolean = false,
    /** Çok fazla eşleşme olduğu için tarama erken bırakıldı. */
    val truncated: Boolean = false,
) {
    val active: PageHit? get() = hits.getOrNull(current)
    val byPage: Map<Int, List<PageHit>> by lazy { hits.groupBy { it.page } }
}

/** Okuyucuya "şu sayfaya, sayfanın şu yüksekliğine git" isteği. */
class PageJump(val page: Int, val yFraction: Float)

class PageReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow<PageReaderState>(PageReaderState.Loading)
    val state: StateFlow<PageReaderState> = _state

    private val _search = MutableStateFlow<PageSearchState?>(null)
    /** null: arama kapalı. */
    val search: StateFlow<PageSearchState?> = _search

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage

    private val jumpChannel = Channel<PageJump>(Channel.CONFLATED)
    val jumps: Flow<PageJump> = jumpChannel.receiveAsFlow()

    val bookmarks: Flow<List<BookmarkEntity>> = container.bookmarks.observe(bookId)

    private var searchJob: Job? = null

    private var book: BookEntity? = null
    private var pending: PendingDocument? = null
    private var document: MuPdfDocument? = null

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        val b = container.library.get(bookId)
        val format = b?.documentFormat
        if (b == null || format == null) {
            _state.value = PageReaderState.Failed("Dosya kütüphanede bulunamadı")
            return
        }
        book = b
        val result = MuPdfDocument.open(container.appContext, Uri.parse(b.uri), format, b.name)
        handle(result)
    }

    private fun handle(result: OpenResult) {
        when (result) {
            is OpenResult.Opened -> {
                document = result.document
                _currentPage.value = book!!.lastPage.coerceIn(0, result.document.pageCount - 1)
                _state.value = PageReaderState.Ready(book!!, result.document)
            }
            is OpenResult.NeedsPassword -> {
                pending = result.pending
                _state.value = PageReaderState.NeedsPassword(wrongPassword = false)
            }
            is OpenResult.Failed -> _state.value = PageReaderState.Failed(result.message)
        }
    }

    fun submitPassword(password: String) {
        val p = pending ?: return
        viewModelScope.launch {
            val doc = try {
                p.authenticate(password)
            } catch (e: Exception) {
                _state.value = PageReaderState.Failed(e.message ?: "Dosya açılamadı")
                return@launch
            }
            if (doc == null) {
                _state.update { PageReaderState.NeedsPassword(wrongPassword = true) }
            } else {
                pending = null
                document = doc
                _currentPage.value = book!!.lastPage.coerceIn(0, doc.pageCount - 1)
                _state.value = PageReaderState.Ready(book!!, doc)
            }
        }
    }

    /** Okuyucu (yeniden) kurulurken başlanacak sayfa; mod değişince konum korunur. */
    fun startPage(): Int = _currentPage.value

    fun onPageChanged(page: Int) {
        _currentPage.value = page
    }

    fun toggleBookmark() {
        val page = _currentPage.value
        viewModelScope.launch { container.bookmarks.togglePage(bookId, page) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { container.bookmarks.delete(id) }
    }

    fun jumpToPage(page: Int) {
        jumpChannel.trySend(PageJump(page, 0f))
    }

    // --- Arama ---

    fun openSearch() {
        if (_search.value == null) _search.value = PageSearchState()
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    /** Tüm sayfaları sırayla tarar; yeni arama eskisini iptal eder. İlk eşleşme geçerli sayfadan sonraki ilk sonuçtur. */
    fun submitSearch(rawQuery: String) {
        val doc = document ?: return
        val query = rawQuery.trim()
        searchJob?.cancel()
        if (query.isEmpty()) {
            _search.value = PageSearchState()
            return
        }
        val startPage = _currentPage.value
        _search.value = PageSearchState(query = query, searching = true)
        searchJob = viewModelScope.launch {
            val hits = ArrayList<PageHit>()
            var truncated = false
            for (i in 0 until doc.pageCount) {
                if (!isActive) return@launch
                val found = try {
                    doc.searchPage(i, query)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList() // okunamayan sayfa aramayı durdurmaz
                }
                found.forEach { hits.add(PageHit(i, it)) }
                if (hits.size >= MAX_HITS) {
                    truncated = true
                    break
                }
                if (i % 10 == 0) yield()
            }
            val first = hits.indexOfFirst { it.page >= startPage }.let { if (it >= 0) it else if (hits.isEmpty()) -1 else 0 }
            _search.value = PageSearchState(query, hits, first, searching = false, truncated = truncated)
            hits.getOrNull(first)?.let { jumpTo(it) }
        }
    }

    fun nextHit() = moveHit(+1)
    fun previousHit() = moveHit(-1)

    private fun moveHit(delta: Int) {
        val s = _search.value ?: return
        if (s.hits.isEmpty()) return
        val next = ((s.current + delta) % s.hits.size + s.hits.size) % s.hits.size
        _search.value = PageSearchState(s.query, s.hits, next, s.searching, s.truncated)
        jumpTo(s.hits[next])
    }

    private fun jumpTo(hit: PageHit) {
        jumpChannel.trySend(PageJump(hit.page, hit.rects.minOf { it.top }))
    }

    /** Ekran kapansa da tamamlanması için uygulama kapsamında çalışır. */
    fun saveProgress(page: Int, pageCount: Int) {
        if (pageCount <= 0) return
        val progress = (page + 1).toFloat() / pageCount
        container.appScope.launch { container.library.saveProgress(bookId, progress, page) }
    }

    private companion object {
        const val MAX_HITS = 2000
    }

    override fun onCleared() {
        val doc = document
        val p = pending
        container.appScope.launch {
            doc?.close()
            p?.close()
        }
    }
}
