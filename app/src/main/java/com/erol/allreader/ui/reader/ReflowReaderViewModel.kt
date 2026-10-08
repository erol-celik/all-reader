package com.erol.allreader.ui.reader

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erol.allreader.AppContainer
import com.erol.allreader.data.BookEntity
import com.erol.allreader.engine.DocumentFormat
import com.erol.allreader.engine.reflow.Draft
import com.erol.allreader.engine.reflow.LoadedText
import com.erol.allreader.engine.reflow.MarkdownConverter
import com.erol.allreader.engine.reflow.SaveResult
import com.erol.allreader.engine.reflow.TextFileStore
import com.erol.allreader.engine.reflow.TextHtml
import com.erol.allreader.engine.reflow.office.OfficeConverter
import com.erol.allreader.engine.reflow.office.OfficeFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReflowState {
    data object Loading : ReflowState
    data class Failed(val message: String) : ReflowState
    /** [body] okuma görünümü için HTML gövdesi (tema okuyucuda eklenir). */
    class Ready(val book: BookEntity, val format: DocumentFormat, val body: String, val editable: Boolean) : ReflowState
}

enum class ReflowMode { READ, EDIT }

sealed interface ReflowDialog {
    class DraftFound(val draft: Draft) : ReflowDialog
    data object Conflict : ReflowDialog
    class NotEncodable(val charsetName: String) : ReflowDialog
    data object NeedWritePermission : ReflowDialog
    data object ConfirmExit : ReflowDialog
    class Error(val message: String) : ReflowDialog
}

class ReflowReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
) : ViewModel() {
    private val library = container.library
    private val store = container.textFiles

    private val _state = MutableStateFlow<ReflowState>(ReflowState.Loading)
    val state: StateFlow<ReflowState> = _state

    private val _mode = MutableStateFlow(ReflowMode.READ)
    val mode: StateFlow<ReflowMode> = _mode

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _dialog = MutableStateFlow<ReflowDialog?>(null)
    val dialog: StateFlow<ReflowDialog?> = _dialog

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Düzenleme sırasında metni tutar; ViewModel ekran döndürmeden sağ çıkar. */
    var editor: TextFieldState? = null
        private set

    val bookmarks: kotlinx.coroutines.flow.Flow<List<com.erol.allreader.data.BookmarkEntity>> = container.bookmarks.observe(bookId)

    fun toggleBookmark(ratio: Float) {
        viewModelScope.launch { container.bookmarks.toggleRatio(bookId, ratio.coerceIn(0f, 1f)) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { container.bookmarks.delete(id) }
    }

    private var loaded: LoadedText? = null
    private var book: BookEntity? = null
    private var format: DocumentFormat? = null
    private var editJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val b = library.get(bookId)
        val f = b?.documentFormat
        if (b == null || f == null) {
            _state.value = ReflowState.Failed("Dosya kütüphanede bulunamadı")
            return
        }
        book = b
        format = f
        try {
            if (f == DocumentFormat.DOCX || f == DocumentFormat.XLSX || f == DocumentFormat.PPTX) {
                val html = withContext(Dispatchers.IO) {
                    val input = container.appContext.contentResolver.openInputStream(Uri.parse(b.uri))
                        ?: throw java.io.IOException("Dosya açılamadı")
                    input.use { OfficeConverter.toHtml(f, it) }
                }
                _state.value = ReflowState.Ready(b, f, html, editable = false)
                return
            }
            val text = store.read(Uri.parse(b.uri))
            loaded = text
            val editable = text.sizeBytes <= TextFileStore.MAX_EDIT_BYTES
            _state.value = ReflowState.Ready(b, f, render(f, text.text), editable)
        } catch (e: Exception) {
            _state.value = ReflowState.Failed(
                if (e is OfficeFormatException) e.message ?: "Dosya açılamadı"
                else "Dosya açılamadı: ${e.message ?: e.javaClass.simpleName}",
            )
        } catch (e: OutOfMemoryError) {
            _state.value = ReflowState.Failed("Dosya belleğe sığmayacak kadar büyük")
        }
    }

    private suspend fun render(format: DocumentFormat, text: String): String =
        withContext(Dispatchers.Default) { TextHtml.render(format, text) }

    /** Markdown önizlemesi için o anki düzenleme metninden HTML. */
    suspend fun previewBody(): String {
        val text = editor?.text?.toString() ?: loaded?.text ?: ""
        return withContext(Dispatchers.Default) { MarkdownConverter.toHtml(text) }
    }

    // --- Düzenleme ---

    fun requestEdit() {
        val s = _state.value as? ReflowState.Ready ?: return
        if (!s.editable) {
            _message.value = "Dosya düzenleme için çok büyük (en fazla 2 MB)"
            return
        }
        if (s.book.rootUri == com.erol.allreader.library.LibraryRepository.EXTERNAL_ROOT) {
            _message.value = "Düzenlemek için dosyayı kütüphane klasöründen aç"
            return
        }
        if (!library.hasWriteAccess(s.book.rootUri)) {
            _dialog.value = ReflowDialog.NeedWritePermission
            return
        }
        viewModelScope.launch {
            val draft = store.loadDraft(bookId)
            val base = loaded
            if (draft != null && base != null && draft.text != base.text) {
                _dialog.value = ReflowDialog.DraftFound(draft)
            } else {
                startEditing(base?.text ?: "", dirty = false)
            }
        }
    }

    fun useDraft(draft: Draft) {
        _dialog.value = null
        startEditing(draft.text, dirty = true)
    }

    fun dropDraft() {
        _dialog.value = null
        viewModelScope.launch {
            store.clearDraft(bookId)
            startEditing(loaded?.text ?: "", dirty = false)
        }
    }

    private fun startEditing(text: String, dirty: Boolean) {
        val state = TextFieldState(text)
        editor = state
        _dirty.value = dirty
        _mode.value = ReflowMode.EDIT
        editJob?.cancel()
        editJob = viewModelScope.launch {
            var pendingDraft: Job? = null
            // snapshotFlow yalnızca metin gerçekten değişince yayar (imleç hareketi sayılmaz).
            // İlk değer (başlangıç metni) atlanır.
            snapshotFlow { state.text.toString() }.drop(1).collect { current ->
                // Özgün metne geri dönüldüyse (geri al) "kaydedilmemiş" sayılmaz.
                _dirty.value = current != loaded?.text
                pendingDraft?.cancel()
                pendingDraft = launch {
                    delay(DRAFT_DEBOUNCE_MS)
                    if (current != loaded?.text) store.saveDraft(bookId, current, loaded?.lastModified)
                    else store.clearDraft(bookId)
                }
            }
        }
    }

    fun save(overwriteConflict: Boolean = false, allowUtf8Fallback: Boolean = false) {
        val b = book ?: return
        val base = loaded ?: return
        val f = format ?: return
        val text = editor?.text?.toString() ?: return
        if (_saving.value) return
        _dialog.value = null
        _saving.value = true
        viewModelScope.launch {
            try {
                when (val result = store.save(Uri.parse(b.uri), bookId, text, base, overwriteConflict, allowUtf8Fallback)) {
                    is SaveResult.Saved -> {
                        loaded = result.loaded
                        store.clearDraft(bookId)
                        library.updateFileInfo(bookId, result.loaded.sizeBytes, result.loaded.lastModified ?: b.modifiedAt)
                        _dirty.value = false
                        val s = _state.value as? ReflowState.Ready
                        if (s != null) _state.value = ReflowState.Ready(s.book, f, render(f, text), s.editable)
                        _message.value = "Kaydedildi"
                    }
                    SaveResult.Conflict -> _dialog.value = ReflowDialog.Conflict
                    is SaveResult.NotEncodable -> _dialog.value = ReflowDialog.NotEncodable(result.charsetName)
                    is SaveResult.Failed -> _dialog.value = ReflowDialog.Error("Kaydedilemedi: ${result.message}")
                }
            } finally {
                _saving.value = false
            }
        }
    }

    /** Geri tuşu/oku düğmesi: değişiklik varsa sorar, yoksa okuma moduna döner. */
    fun requestExitEdit() {
        if (_mode.value != ReflowMode.EDIT) return
        if (_dirty.value) _dialog.value = ReflowDialog.ConfirmExit else leaveEdit()
    }

    fun discardAndExit() {
        _dialog.value = null
        viewModelScope.launch { store.clearDraft(bookId) }
        leaveEdit()
    }

    private fun leaveEdit() {
        editJob?.cancel()
        editor = null
        _dirty.value = false
        _mode.value = ReflowMode.READ
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    // --- Yazma izni ---

    /** Kullanıcı klasörü yeniden seçti: izni kalıcı yap, tara, tekrar dene. */
    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            library.persistFolder(uri)
            library.rescan()
            val refreshed = library.get(bookId)
            val s = _state.value as? ReflowState.Ready
            if (refreshed != null && s != null) {
                book = refreshed
                _state.value = ReflowState.Ready(refreshed, s.format, s.body, s.editable)
                if (library.hasWriteAccess(refreshed.rootUri)) {
                    _dialog.value = null
                    requestEdit()
                } else {
                    _dialog.value = ReflowDialog.Error("Yazma izni verilmedi. Dosyanın bulunduğu klasörü seçtiğinden emin ol.")
                }
            }
        }
    }

    fun saveProgress(ratio: Float) {
        container.appScope.launch(NonCancellable) { library.saveProgress(bookId, ratio, 0, ratio) }
    }

    private companion object {
        const val DRAFT_DEBOUNCE_MS = 1000L
    }
}
