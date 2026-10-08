package com.erol.allreader.ui.reader

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.erol.allreader.AllReaderApp
import com.erol.allreader.data.SettingsRepository
import com.erol.allreader.engine.DocumentFormat
import com.erol.allreader.engine.reflow.ReaderHtml
import com.erol.allreader.engine.reflow.ReaderTheme
import kotlin.math.max
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun ReflowReaderScreen(bookId: Long, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AllReaderApp).container
    val vm: ReflowReaderViewModel = viewModel(
        key = "reflow-reader-$bookId",
        factory = viewModelFactory { initializer { ReflowReaderViewModel(container, bookId) } },
    )
    val state by vm.state.collectAsState()
    val mode by vm.mode.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val saving by vm.saving.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val message by vm.message.collectAsState()
    val theme by container.settings.readerTheme.collectAsState(initial = ReaderTheme.SYSTEM)
    val fontPercent by container.settings.fontPercent.collectAsState(initial = SettingsRepository.DEFAULT_FONT_PERCENT)
    val systemDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var currentRatio by remember { mutableFloatStateOf(0f) }
    val web = remember { WebController() }
    val bookmarks by vm.bookmarks.collectAsState(initial = emptyList())

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val editing = mode == ReflowMode.EDIT
    BackHandler(enabled = editing) { vm.requestExitEdit() }
    val searching = searchOpen && !editing
    FullscreenEffect(enabled = !chromeVisible && !searching && !editing)
    BackHandler(enabled = searching) {
        web.clear()
        searchOpen = false
    }

    val ready = state as? ReflowState.Ready
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (searching && web.query.isNotEmpty()) {
                val summary = when {
                    web.counting && web.matchCount == 0 -> "Aranıyor…"
                    web.matchCount == 0 -> "Eşleşme yok"
                    else -> "${web.matchIndex + 1} / ${web.matchCount}"
                }
                SearchNavBar(
                    summary = summary,
                    searching = false,
                    canNavigate = web.matchCount > 0,
                    onPrevious = { web.next(false) },
                    onNext = { web.next(true) },
                )
            }
        },
        topBar = {
            if (!chromeVisible && !searching && !editing) return@Scaffold
            if (searching) {
                SearchTopBar(
                    initialQuery = web.query,
                    onSubmit = web::find,
                    onClose = {
                        web.clear()
                        searchOpen = false
                    },
                )
                return@Scaffold
            }
            TopAppBar(
                title = {
                    val name = ready?.book?.name ?: ""
                    Text(if (editing && dirty) "• $name" else name, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = { if (editing) vm.requestExitEdit() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    if (ready != null && !editing) {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Ara")
                        }
                        IconButton(onClick = { vm.toggleBookmark(currentRatio) }) {
                            BookmarkToggleIcon(
                                marked = bookmarks.any {
                                    !it.isPage && kotlin.math.abs(it.ratio - currentRatio) <= com.erol.allreader.data.BookmarkRepository.REFLOW_TOLERANCE
                                },
                            )
                        }
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Filled.Bookmarks, contentDescription = "Yer imleri")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.TextFields, contentDescription = "Okuma ayarları")
                        }
                        if (ready.format == DocumentFormat.TXT || ready.format == DocumentFormat.MARKDOWN) {
                            IconButton(onClick = vm::requestEdit) {
                                Icon(Icons.Filled.Edit, contentDescription = "Düzenle")
                            }
                        }
                    }
                    if (editing) {
                        val undo = vm.editor?.undoState
                        IconButton(onClick = { undo?.undo() }, enabled = undo?.canUndo == true) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Geri al")
                        }
                        IconButton(onClick = { undo?.redo() }, enabled = undo?.canRedo == true) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Yinele")
                        }
                        IconButton(onClick = { vm.save() }, enabled = dirty && !saving) {
                            Icon(Icons.Filled.Save, contentDescription = "Kaydet")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ReflowState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ReflowState.Failed -> FailureMessage(s.message, onBack)
                is ReflowState.Ready -> {
                    if (editing) {
                        EditorPane(vm, s, theme, systemDark, fontPercent)
                    } else {
                        ReadingPane(
                            html = ReaderHtml.wrap(s.body, theme, systemDark),
                            bgColor = ReaderHtml.backgroundColor(theme, systemDark).toInt(),
                            fontPercent = fontPercent,
                            initialRatio = s.book.lastOffset,
                            onRatio = { vm.saveProgress(it) },
                            onLiveRatio = { currentRatio = it },
                            onTap = { chromeVisible = !chromeVisible },
                            controller = web,
                        )
                    }
                }
            }
            if (saving) CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
    }

    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onOpen = {
                showBookmarks = false
                web.scrollToRatio(it.ratio)
            },
            onDelete = { vm.deleteBookmark(it.id) },
            onDismiss = { showBookmarks = false },
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            theme = theme,
            fontPercent = fontPercent,
            onTheme = { scope.launch { container.settings.setReaderTheme(it) } },
            onFont = { scope.launch { container.settings.setFontPercent(it) } },
            onDismiss = { showSettings = false },
        )
    }

    when (val d = dialog) {
        null -> Unit
        is ReflowDialog.DraftFound -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Kaydedilmemiş taslak bulundu") },
            text = { Text("Bu dosyada daha önce kaydedilmemiş değişiklikler var. Taslağı açmak ister misin?") },
            confirmButton = { TextButton(onClick = { vm.useDraft(d.draft) }) { Text("Taslağı aç") } },
            dismissButton = { TextButton(onClick = vm::dropDraft) { Text("Taslağı sil") } },
        )
        ReflowDialog.Conflict -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Dosya değişmiş") },
            text = { Text("Dosya sen düzenlerken başka bir yerde değiştirilmiş. Üzerine yazarsan o değişiklikler kaybolur.") },
            confirmButton = { TextButton(onClick = { vm.save(overwriteConflict = true) }) { Text("Üzerine yaz") } },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Vazgeç") } },
        )
        is ReflowDialog.NotEncodable -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Karakterler kaydedilemiyor") },
            text = { Text("Metindeki bazı karakterler dosyanın kodlamasında (${d.charsetName}) yok. UTF-8 olarak kaydedilsin mi?") },
            confirmButton = { TextButton(onClick = { vm.save(allowUtf8Fallback = true) }) { Text("UTF-8 kaydet") } },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Vazgeç") } },
        )
        ReflowDialog.NeedWritePermission -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Yazma izni gerekli") },
            text = { Text("Dosyayı düzenlemek için bulunduğu klasöre yazma izni vermelisin. Aynı klasörü bir kez daha seç.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissDialog()
                    pickFolder.launch(ready?.book?.rootUri?.let { android.net.Uri.parse(it) })
                }) { Text("Klasörü seç") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Vazgeç") } },
        )
        ReflowDialog.ConfirmExit -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Değişiklikler kaydedilsin mi?") },
            confirmButton = { TextButton(onClick = { vm.save() }) { Text("Kaydet") } },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::dismissDialog) { Text("İptal") }
                    TextButton(onClick = vm::discardAndExit) { Text("Kaydetme") }
                }
            },
        )
        is ReflowDialog.Error -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Hata") },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = vm::dismissDialog) { Text("Tamam") } },
        )
    }
}

@Composable
private fun FailureMessage(message: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Kütüphaneye dön") }
    }
}

// --- Okuma ---

@Composable
@OptIn(FlowPreview::class)
private fun ReadingPane(
    html: String,
    bgColor: Int,
    fontPercent: Int,
    initialRatio: Float,
    onRatio: (Float) -> Unit,
    onLiveRatio: (Float) -> Unit,
    controller: WebController,
    onTap: () -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(initialRatio) }
    val latestOnRatio by rememberUpdatedState(onRatio)
    LaunchedEffect(Unit) {
        snapshotFlow { ratio }.debounce(500).collect { latestOnRatio(it) }
    }
    DisposableEffect(Unit) { onDispose { latestOnRatio(ratio) } }

    ReflowWebView(
        html = html,
        bgColor = bgColor,
        fontPercent = fontPercent,
        initialRatio = initialRatio,
        onRatio = {
            ratio = it
            onLiveRatio(it)
        },
        controller = controller,
        onTap = onTap,
        modifier = Modifier.fillMaxSize(),
    )
}

/** WebView'ü ekran düzeyinden yönetir: aramayı ve konuma atlamayı yürütür, sonuç sayılarını tutar. */
@Stable
class WebController {
    var view: WebView? = null
    var query by mutableStateOf("")
        private set
    var matchIndex by mutableIntStateOf(0)
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var counting by mutableStateOf(false)
        private set

    fun onFindResult(active: Int, total: Int, done: Boolean) {
        matchIndex = active
        matchCount = total
        counting = !done
    }

    fun find(text: String) {
        val q = text.trim()
        query = q
        matchCount = 0
        matchIndex = 0
        if (q.isEmpty()) {
            view?.clearMatches()
        } else {
            counting = true
            view?.findAllAsync(q)
        }
    }

    fun next(forward: Boolean) {
        view?.findNext(forward)
    }

    fun clear() {
        view?.clearMatches()
        query = ""
        matchCount = 0
        matchIndex = 0
        counting = false
    }

    fun scrollToRatio(ratio: Float) {
        val v = view ?: return
        v.scrollTo(0, (ratio.coerceIn(0f, 1f) * scrollRange(v)).toInt())
    }
}

private class WebState(var restoreRatio: Float, var restored: Boolean = false, var loadedHtml: String? = null)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReflowWebView(
    html: String,
    bgColor: Int,
    fontPercent: Int,
    initialRatio: Float,
    onRatio: (Float) -> Unit,
    modifier: Modifier = Modifier,
    controller: WebController? = null,
    onTap: () -> Unit = {},
) {
    val webState = remember { WebState(restoreRatio = initialRatio) }
    val latestOnRatio by rememberUpdatedState(onRatio)
    val latestOnTap by rememberUpdatedState(onTap)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    // Tamamen çevrimdışı ve güvenli: betik, ağ ve dosya erişimi kapalı.
                    javaScriptEnabled = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    safeBrowsingEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                isVerticalScrollBarEnabled = true
                // Tek dokunuş: tam ekran okuma için çubukları göster/gizle.
                val tapDetector = android.view.GestureDetector(
                    ctx,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                            latestOnTap()
                            return false
                        }
                    },
                )
                setOnTouchListener { _, ev ->
                    tapDetector.onTouchEvent(ev)
                    false
                }
                controller?.let { c ->
                    c.view = this
                    setFindListener { active, total, done -> c.onFindResult(active, total, done) }
                }
                webViewClient = object : WebViewClient() {
                    // Bağlantılara tıklanınca gezinme yok (hiçbir şey ağa gitmez).
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.postDelayed({ restoreWhenStable(view, webState) }, 100)
                    }
                }
                setOnScrollChangeListener { v, _, y, _, _ ->
                    // Geri yükleme bitmeden gelen 0 konumu kayıtlı ilerlemenin üzerine yazmasın.
                    if (webState.restored) {
                        val range = scrollRange(v as WebView)
                        val r = if (range <= 0) 0f else (y.toFloat() / range).coerceIn(0f, 1f)
                        webState.restoreRatio = r
                        latestOnRatio(r)
                    }
                }
            }
        },
        update = { wv ->
            wv.settings.textZoom = fontPercent
            wv.setBackgroundColor(bgColor)
            if (webState.loadedHtml != html) {
                // Tema/içerik değişince aynı konumda kal.
                if (webState.loadedHtml != null && webState.restored) {
                    val range = scrollRange(wv)
                    if (range > 0) webState.restoreRatio = (wv.scrollY.toFloat() / range).coerceIn(0f, 1f)
                }
                webState.restored = false
                webState.loadedHtml = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { wv ->
            if (controller?.view === wv) controller.view = null
            wv.stopLoading()
            wv.destroy()
        },
    )
}

/**
 * Kayıtlı konuma kaydırır. WebView içeriği yerleşirken yükseklik büyüyebildiği için, yükseklik iki
 * ölçüm arka arkaya aynı kalana kadar (en fazla ~1.5 sn) konumu yeniden uygular.
 */
private fun restoreWhenStable(view: WebView, state: WebState, attempt: Int = 0, lastHeight: Int = -1) {
    val height = view.contentHeight
    val ratio = state.restoreRatio
    if (ratio > 0f) view.scrollTo(0, (ratio * scrollRange(view)).toInt())
    if (attempt >= 12 || (height > 0 && height == lastHeight)) {
        state.restored = true
        return
    }
    view.postDelayed({ restoreWhenStable(view, state, attempt + 1, height) }, 120)
}

@Suppress("DEPRECATION")
private fun scrollRange(wv: WebView): Int = max(0, (wv.contentHeight * wv.scale).toInt() - wv.height)

// --- Düzenleme ---

@Composable
private fun EditorPane(
    vm: ReflowReaderViewModel,
    s: ReflowState.Ready,
    theme: ReaderTheme,
    systemDark: Boolean,
    fontPercent: Int,
) {
    val editor = vm.editor ?: return
    val isMarkdown = s.format == DocumentFormat.MARKDOWN
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        if (isMarkdown) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Düzenle") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Önizle") })
            }
        }
        if (tab == 0) {
            val size = MaterialTheme.typography.bodyLarge.fontSize * (fontPercent / 100f)
            BasicTextField(
                state = editor,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = size,
                    lineHeight = size * 1.5f,
                    fontFamily = if (isMarkdown) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = TextFieldLineLimits.MultiLine(),
                scrollState = rememberScrollState(),
                decorator = { inner -> Box(Modifier.fillMaxWidth().padding(16.dp)) { inner() } },
            )
        } else {
            val body by produceState("", tab) { value = vm.previewBody() }
            ReflowWebView(
                html = ReaderHtml.wrap(body, theme, systemDark),
                bgColor = ReaderHtml.backgroundColor(theme, systemDark).toInt(),
                fontPercent = fontPercent,
                initialRatio = 0f,
                onRatio = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// --- Okuma ayarları ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    theme: ReaderTheme,
    fontPercent: Int,
    onTheme: (ReaderTheme) -> Unit,
    onFont: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Tema", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { t ->
                    FilterChip(selected = t == theme, onClick = { onTheme(t) }, label = { Text(t.label) })
                }
            }
            Text("Yazı boyutu: %$fontPercent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp))
            Slider(
                value = fontPercent.toFloat(),
                onValueChange = { onFont((it / 10).toInt() * 10) },
                valueRange = SettingsRepository.MIN_FONT_PERCENT.toFloat()..SettingsRepository.MAX_FONT_PERCENT.toFloat(),
                steps = (SettingsRepository.MAX_FONT_PERCENT - SettingsRepository.MIN_FONT_PERCENT) / 10 - 1,
            )
        }
    }
}
