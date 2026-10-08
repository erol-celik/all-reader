package com.erol.allreader.ui.library

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import com.erol.allreader.AllReaderApp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.erol.allreader.data.BookEntity
import com.erol.allreader.engine.DocumentFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (BookEntity) -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.addFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kütüphane") },
                actions = {
                    IconButton(onClick = viewModel::rescan, enabled = !state.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Yeniden tara")
                    }
                    IconButton(onClick = { pickFolder.launch(null) }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "Klasör ekle")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !state.loaded -> Unit
                state.folderCount == 0 -> EmptyState(
                    text = "Henüz klasör eklenmedi.\nBelgelerinin bulunduğu klasörü seç.",
                    action = "Klasör ekle",
                    onAction = { pickFolder.launch(null) },
                )
                else -> BookList(state, viewModel::setQuery, onOpen = {
                    viewModel.onOpen(it)
                    onOpenBook(it)
                })
            }
        }
    }
}

@Composable
private fun EmptyState(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action) }
        }
    }
}

@Composable
private fun BookList(state: LibraryUiState, onQuery: (String) -> Unit, onOpen: (BookEntity) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Dosya ara") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Aramayı temizle")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.query.isBlank() && state.recent.isNotEmpty()) {
            item { SectionTitle("Son okunanlar") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.recent, key = { it.id }) { RecentCard(it, onOpen) }
                }
            }
        }
        item { SectionTitle("Tüm dosyalar (${state.books.size})") }
        if (state.books.isEmpty()) {
            item {
                val text = if (state.query.isBlank()) "Seçili klasörlerde desteklenen dosya bulunamadı." else "Eşleşen dosya yok."
                Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            items(state.books, key = { it.id }) { BookRow(it, onOpen) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun RecentCard(book: BookEntity, onOpen: (BookEntity) -> Unit) {
    Card(Modifier.width(140.dp).clickable { onOpen(book) }) {
        Column(Modifier.padding(12.dp)) {
            Cover(book, Modifier.size(width = 56.dp, height = 78.dp))
            Text(
                book.name,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (book.progress > 0f) {
                Text("%${(book.progress * 100).toInt()}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BookRow(book: BookEntity, onOpen: (BookEntity) -> Unit) {
    val context = LocalContext.current
    val format = book.documentFormat
    val subtitle = buildList {
        add(format?.label ?: "?")
        add(Formatter.formatShortFileSize(context, book.sizeBytes))
        if (book.progress > 0f) add("%${(book.progress * 100).toInt()}")
    }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(book.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Cover(book, Modifier.size(width = 40.dp, height = 56.dp)) },
        modifier = Modifier.clickable { onOpen(book) },
    )
}

/** Kapak küçük resmi; yoksa (sayfa motoru dışı, şifreli, bozuk) format simgesi. */
@Composable
private fun Cover(book: BookEntity, modifier: Modifier) {
    val covers = (LocalContext.current.applicationContext as AllReaderApp).container.covers
    val cover by produceState<Bitmap?>(null, book.id, book.modifiedAt, book.sizeBytes) { value = covers.load(book) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(iconFor(book.documentFormat), contentDescription = null)
        }
    }
}

private fun iconFor(format: DocumentFormat?): ImageVector = when (format) {
    DocumentFormat.PDF -> Icons.Filled.PictureAsPdf
    DocumentFormat.IMAGE, DocumentFormat.CBZ -> Icons.Filled.Image
    DocumentFormat.XLSX -> Icons.Filled.TableChart
    DocumentFormat.PPTX -> Icons.Filled.Slideshow
    else -> Icons.Filled.Description
}
