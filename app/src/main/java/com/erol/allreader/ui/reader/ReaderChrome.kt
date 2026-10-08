package com.erol.allreader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.erol.allreader.data.PageMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.erol.allreader.data.BookmarkEntity

/** Arama modunda üst çubuk: geri (aramayı kapat) + metin alanı. Enter/ara tuşu aramayı başlatır. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(initialQuery: String, onSubmit: (String) -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { if (initialQuery.isEmpty()) focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Aramayı kapat") }
        },
        title = {
            TextField(
                value = text,
                onValueChange = { text = it.take(MAX_QUERY) },
                singleLine = true,
                placeholder = { Text("Belgede ara") },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    onSubmit(text)
                }),
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, contentDescription = "Temizle") }
                    }
                },
            )
        },
    )
}

private const val MAX_QUERY = 200

/** Aramanın altındaki gezinme çubuğu: "3 / 12" + önceki/sonraki. */
@Composable
fun SearchNavBar(
    summary: String,
    searching: Boolean,
    canNavigate: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (searching) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                IconButton(onClick = onPrevious, enabled = canNavigate) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Önceki eşleşme")
                }
                IconButton(onClick = onNext, enabled = canNavigate) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Sonraki eşleşme")
                }
            }
        }
    }
}

/** Yer imi simgesi: bu konumda yer imi varsa dolu. */
@Composable
fun BookmarkToggleIcon(marked: Boolean) {
    Icon(
        if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
        contentDescription = if (marked) "Yer imini kaldır" else "Yer imi ekle",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Yer imleri", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (bookmarks.isEmpty()) {
            Text(
                "Henüz yer imi yok. Üstteki yer imi simgesiyle bulunduğun konumu ekleyebilirsin.",
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            )
        } else {
            LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                items(bookmarks, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(b) }.padding(start = 24.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(b.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 14.dp))
                        IconButton(onClick = { onDelete(b) }) { Icon(Icons.Filled.Delete, contentDescription = "Yer imini sil") }
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** [enabled] iken sistem çubuklarını gizler (tam ekran okuma); ekrandan çıkınca geri getirir. */
@Composable
fun FullscreenEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Sayfa motoru okuma ayarları: gece modu ve kaydırma/sayfa modu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSettingsSheet(
    night: Boolean,
    mode: PageMode,
    onNight: (Boolean) -> Unit,
    onMode: (PageMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).navigationBarsPadding()) {
            Text("Okuma modu", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageMode.entries.forEach { m ->
                    FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(m.label) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Gece modu (renkleri ters çevir)", style = MaterialTheme.typography.titleSmall)
                Switch(checked = night, onCheckedChange = onNight)
            }
        }
    }
}
