package com.erol.allreader.ui.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import com.erol.allreader.data.PageMode
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.geometry.Size
import com.erol.allreader.data.BookmarkEntity
import com.erol.allreader.engine.page.FractionRect
import kotlinx.coroutines.flow.Flow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.erol.allreader.AllReaderApp
import com.erol.allreader.engine.page.MuPdfDocument
import com.erol.allreader.engine.page.PageLayoutMath
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val DOUBLE_TAP_ZOOM = 2.5f

/** Renkleri ters çevirir (gece modu): beyaz kağıt siyah, siyah metin beyaz olur. */
private val INVERT_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageReaderScreen(bookId: Long, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AllReaderApp).container
    val vm: PageReaderViewModel = viewModel(
        key = "page-reader-$bookId",
        factory = viewModelFactory { initializer { PageReaderViewModel(container, bookId) } },
    )
    val state by vm.state.collectAsState()
    val search by vm.search.collectAsState()
    val currentPage by vm.currentPage.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState(initial = emptyList())
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val night by container.settings.pageNightMode.collectAsState(initial = false)
    val mode by container.settings.pageMode.collectAsState(initial = PageMode.SCROLL)
    val scope = rememberCoroutineScope()
    val ready = state as? PageReaderState.Ready

    FullscreenEffect(enabled = !chromeVisible && search == null)

    BackHandler(enabled = search != null) { vm.closeSearch() }

    Scaffold(
        topBar = {
            val s = search
            if (s == null && !chromeVisible) return@Scaffold
            if (s != null) {
                SearchTopBar(initialQuery = s.query, onSubmit = vm::submitSearch, onClose = vm::closeSearch)
            } else {
                TopAppBar(
                    title = { Text(ready?.book?.name ?: "", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                        }
                    },
                    actions = {
                        if (ready != null) {
                            IconButton(onClick = vm::openSearch) {
                                Icon(Icons.Filled.Search, contentDescription = "Ara")
                            }
                            IconButton(onClick = vm::toggleBookmark) {
                                BookmarkToggleIcon(marked = bookmarks.any { it.page == currentPage })
                            }
                            IconButton(onClick = { showBookmarks = true }) {
                                Icon(Icons.Filled.Bookmarks, contentDescription = "Yer imleri")
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Filled.Tune, contentDescription = "Okuma ayarları")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            val s = search
            if (s != null && (s.searching || s.query.isNotEmpty())) {
                val summary = when {
                    s.searching -> "Aranıyor…"
                    s.hits.isEmpty() -> "Eşleşme yok"
                    else -> "${s.current + 1} / ${s.hits.size}" + if (s.truncated) "+" else ""
                }
                SearchNavBar(
                    summary = summary,
                    searching = s.searching,
                    canNavigate = s.hits.isNotEmpty(),
                    onPrevious = vm::previousHit,
                    onNext = vm::nextHit,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                PageReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is PageReaderState.Failed -> FailureMessage(s.message, onBack)
                is PageReaderState.NeedsPassword -> PasswordDialog(
                    wrong = s.wrongPassword,
                    onSubmit = vm::submitPassword,
                    onCancel = onBack,
                )
                is PageReaderState.Ready -> key(mode) {
                    if (mode == PageMode.SCROLL) {
                        PageReader(
                            doc = s.document,
                            initialPage = vm.startPage(),
                            onProgress = { page -> vm.saveProgress(page, s.document.pageCount) },
                            onPageChanged = vm::onPageChanged,
                            jumps = vm.jumps,
                            search = search,
                            night = night,
                            onTap = { chromeVisible = !chromeVisible },
                        )
                    } else {
                        PagedReader(
                            doc = s.document,
                            initialPage = vm.startPage(),
                            onProgress = { page -> vm.saveProgress(page, s.document.pageCount) },
                            onPageChanged = vm::onPageChanged,
                            jumps = vm.jumps,
                            search = search,
                            night = night,
                            onTap = { chromeVisible = !chromeVisible },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        PageSettingsSheet(
            night = night,
            mode = mode,
            onNight = { scope.launch { container.settings.setPageNightMode(it) } },
            onMode = { scope.launch { container.settings.setPageMode(it) } },
            onDismiss = { showSettings = false },
        )
    }

    if (showBookmarks) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onOpen = {
                showBookmarks = false
                vm.jumpToPage(it.page)
            },
            onDelete = { vm.deleteBookmark(it.id) },
            onDismiss = { showBookmarks = false },
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
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Kütüphaneye dön") }
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Şifreli belge") },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = wrong,
                    label = { Text("Parola") },
                    visualTransformation = PasswordVisualTransformation(),
                    // KeyboardType.Password parola yöneticilerini ("Parolayı kaydet?") tetikler; belge
                    // parolası için istenmez. Metin tipi + maskeleme aynı görünümü verir.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        autoCorrectEnabled = false,
                    ),
                )
                if (wrong) Text("Parola yanlış", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Aç") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("İptal") } },
    )
}

/** Sayfa bitmap'leri için bellek önbelleği; anahtar (sayfa, genişlik kovası). */
private class PageBitmapCache(maxBytes: Int) {
    private data class Key(val index: Int, val width: Int)

    private val lru = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    fun get(index: Int, width: Int): Bitmap? = lru.get(Key(index, width))

    /** Aynı sayfanın herhangi bir (örn. düşük çözünürlüklü) önbellekteki hali. */
    fun any(index: Int): Bitmap? = lru.snapshot().entries.firstOrNull { it.key.index == index }?.value

    fun put(index: Int, width: Int, bitmap: Bitmap) {
        lru.put(Key(index, width), bitmap)
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun PageReader(
    doc: MuPdfDocument,
    initialPage: Int,
    onProgress: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    jumps: Flow<PageJump>,
    search: PageSearchState?,
    night: Boolean,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }
    val math = remember(doc, gapPx) { PageLayoutMath(doc.aspects, gapPx) }
    val cache = remember(doc) { PageBitmapCache(maxBytes = 40 * 1024 * 1024) }

    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    // Pinch sırasındaki geçici (yalnızca görsel) ölçek ve odak noktası.
    var live by remember { mutableFloatStateOf(1f) }
    var liveFocus by remember { mutableStateOf(Offset.Zero) }
    var scrollRequest by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // (id, sayfa, offset)
    var scrollRequestId by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(0, doc.pageCount - 1))

    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { latestOnPageChanged(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(400).collect { latestOnProgress(it) }
    }
    DisposableEffect(listState) { onDispose { latestOnProgress(listState.firstVisibleItemIndex) } }

    LaunchedEffect(scrollRequest) {
        scrollRequest?.let { (_, page, offset) -> listState.scrollToItem(page, offset) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3A3A3A))) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        val effectivePan = PageLayoutMath.clampPan(panX, viewportW, zoom)
        val contentW = (viewportW * zoom).roundToInt().coerceAtLeast(1)

        // Arama sonucu / yer imi atlaması: hedef sayfanın içinde ilgili yüksekliğe, ekranın üst çeyreğine kaydır.
        LaunchedEffect(doc) {
            jumps.collect { j ->
                val page = j.page.coerceIn(0, doc.pageCount - 1)
                val pageH = math.pageHeight(page, viewportW * zoom)
                val offset = (j.yFraction * pageH - viewportH / 4f).coerceIn(0f, (pageH - 1f).coerceAtLeast(0f))
                listState.scrollToItem(page, offset.roundToInt())
            }
        }

        /** Yakınlaştırmayı kalıcı hale getirir; odak noktası ekranda sabit kalır. */
        fun commitZoom(ratio: Float, focus: Offset) {
            val newZoom = (zoom * ratio).coerceIn(PageLayoutMath.MIN_ZOOM, PageLayoutMath.MAX_ZOOM)
            val r = newZoom / zoom
            if (r == 1f) return
            val oldW = viewportW * zoom
            val newW = viewportW * newZoom
            val absY = math.absoluteY(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset.toFloat(), oldW) + focus.y
            val (page, offIn) = math.locate(absY, oldW)
            val newAbsAtFocus = math.pageTop(page, newW) + offIn * r
            val (newPage, newOffset) = math.locate(newAbsAtFocus - focus.y, newW)
            panX = PageLayoutMath.panAfterZoom(effectivePan, focus.x, r, viewportW, newZoom)
            zoom = newZoom
            scrollRequestId += 1
            scrollRequest = Triple(scrollRequestId, newPage, newOffset)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(doc, viewportW) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                        if (zoom > 1.01f) commitZoom(1f / zoom, tap) else commitZoom(DOUBLE_TAP_ZOOM, tap)
                    })
                }
                .pointerInput(doc, viewportW) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        var accumulated = 1f
                        var focus = Offset.Zero
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2) {
                                pinching = true
                                accumulated = (accumulated * event.calculateZoom()).coerceIn(
                                    PageLayoutMath.MIN_ZOOM / zoom,
                                    PageLayoutMath.MAX_ZOOM / zoom,
                                )
                                focus = event.calculateCentroid(useCurrent = true)
                                live = accumulated
                                liveFocus = focus
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (!pinching && zoom > 1.01f) {
                                // Tek parmakla yatay kaydırma; dikey kaydırmayı liste yapar (tüketmiyoruz).
                                val change = event.changes.firstOrNull()
                                if (change != null && change.pressed) {
                                    val dx = change.position.x - change.previousPosition.x
                                    panX = PageLayoutMath.clampPan(panX + dx, viewportW, zoom)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            val ratio = accumulated
                            live = 1f
                            commitZoom(ratio, focus)
                        }
                    }
                },
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(with(density) { gapPx.toDp() }),
                modifier = Modifier
                    .layout { measurable, c ->
                        val placeable = measurable.measure(Constraints.fixed(contentW, c.maxHeight))
                        layout(c.maxWidth, c.maxHeight) { placeable.place(IntOffset(effectivePan.roundToInt(), 0)) }
                    }
                    .graphicsLayer {
                        scaleX = live
                        scaleY = live
                        transformOrigin = TransformOrigin(
                            pivotFractionX = ((liveFocus.x - effectivePan) / contentW).coerceIn(0f, 1f),
                            pivotFractionY = (liveFocus.y / viewportH).coerceIn(0f, 1f),
                        )
                    },
            ) {
                items(doc.pageCount, key = { it }) { index ->
                    PageItem(
                        doc, index, widthPx = contentW, cache = cache,
                        hits = search?.byPage?.get(index).orEmpty(),
                        activeHit = search?.active,
                        night = night,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageItem(
    doc: MuPdfDocument,
    index: Int,
    widthPx: Int,
    cache: PageBitmapCache,
    hits: List<PageHit>,
    activeHit: PageHit?,
    night: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val bucket = ((widthPx + 31) / 32) * 32
    var shown by remember(index) { mutableStateOf(cache.any(index)) }

    LaunchedEffect(index, bucket) {
        val cached = cache.get(index, bucket)
        if (cached != null) {
            shown = cached
        } else {
            try {
                val bitmap = doc.render(index, bucket)
                cache.put(index, bucket, bitmap)
                shown = bitmap
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Çizilemeyen sayfa boş beyaz kalır; diğer sayfalar etkilenmez.
            }
        }
    }

    val aspect = doc.aspects[index].coerceAtLeast(PageLayoutMath.MIN_ASPECT)
    Box(modifier.aspectRatio(1f / aspect).background(if (night) Color.Black else Color.White)) {
        shown?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Sayfa ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = if (night) INVERT_FILTER else null,
            )
        }
        if (hits.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (hit in hits) {
                    val active = hit === activeHit
                    val color = if (active) Color(0x99FF8F00) else Color(0x66FFEB3B)
                    for (r in hit.rects) drawRect(color, Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
                }
            }
        }
    }
}

/**
 * Sayfa sayfa (yatay) okuma: her sayfa ekrana sığar; yakınlaştırınca tek parmakla gezilir ve
 * kaydırma sayfa değiştirmez. Çift dokunuş 1x ↔ 2,5x.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    doc: MuPdfDocument,
    initialPage: Int,
    onProgress: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    jumps: Flow<PageJump>,
    search: PageSearchState?,
    night: Boolean,
    onTap: () -> Unit,
) {
    val cache = remember(doc) { PageBitmapCache(maxBytes = 40 * 1024 * 1024) }
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, doc.pageCount - 1)) { doc.pageCount }
    var zoomed by remember { mutableStateOf(false) }

    val latestOnProgress by rememberUpdatedState(onProgress)
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { latestOnPageChanged(it) }
    }
    LaunchedEffect(pager) {
        @OptIn(FlowPreview::class)
        snapshotFlow { pager.currentPage }.debounce(400).collect { latestOnProgress(it) }
    }
    DisposableEffect(pager) { onDispose { latestOnProgress(pager.currentPage) } }
    LaunchedEffect(doc) {
        jumps.collect { j -> pager.scrollToPage(j.page.coerceIn(0, doc.pageCount - 1)) }
    }

    Box(Modifier.fillMaxSize().background(if (night) Color.Black else Color(0xFF3A3A3A))) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            ZoomablePage(
                doc = doc,
                index = index,
                cache = cache,
                hits = search?.byPage?.get(index).orEmpty(),
                activeHit = search?.active,
                night = night,
                onTap = onTap,
                onZoomChanged = { z -> if (index == pager.currentPage) zoomed = z },
            )
        }
    }
}

@Composable
private fun ZoomablePage(
    doc: MuPdfDocument,
    index: Int,
    cache: PageBitmapCache,
    hits: List<PageHit>,
    activeHit: PageHit?,
    night: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(scale) { latestOnZoomChanged(scale > 1.01f) }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val aspect = doc.aspects[index].coerceAtLeast(PageLayoutMath.MIN_ASPECT)
        // Sayfa ekrana sığacak şekilde (genişlik ya da yükseklik sınırlar).
        val fitW = minOf(viewW, viewH / aspect)
        val fitH = fitW * aspect

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = ((fitW * s - viewW) / 2f).coerceAtLeast(0f)
            val maxY = ((fitH * s - viewH) / 2f).coerceAtLeast(0f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(index, viewW, viewH) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val target = DOUBLE_TAP_ZOOM
                                // Dokunulan noktayı ekranda sabit tut (merkeze göre).
                                val center = Offset(viewW / 2f, viewH / 2f)
                                offset = clamp((center - tap) * (target - 1f), target)
                                scale = target
                            }
                        },
                    )
                }
                .pointerInput(index, viewW, viewH) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, PageLayoutMath.MAX_ZOOM)
                                val pan = event.calculatePan()
                                scale = newScale
                                offset = clamp(offset + pan, newScale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (scale > 1.01f) {
                                val change = event.changes.firstOrNull()
                                if (change != null && change.pressed && change.positionChanged()) {
                                    offset = clamp(offset + (change.position - change.previousPosition), scale)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            PageItem(
                doc, index,
                // Yakınlaştırınca keskin kalsın diye ekran genişliğinin 2 katında çizilir.
                widthPx = (fitW * 2f).roundToInt().coerceAtLeast(1),
                cache = cache,
                hits = hits,
                activeHit = activeHit,
                night = night,
                modifier = Modifier
                    .width(with(density) { fitW.toDp() })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
