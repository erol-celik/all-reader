package com.erol.allreader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.erol.allreader.AllReaderApp
import com.erol.allreader.data.BookEntity
import com.erol.allreader.engine.Engine

/** Kitabın motoruna göre doğru okuyucuyu açar; kitap ya da biçim bilinmiyorsa bilgi ekranı gösterir. */
@Composable
fun ReaderRoute(bookId: Long, onBack: () -> Unit) {
    val repo = (LocalContext.current.applicationContext as AllReaderApp).container.library
    // (yüklendi mi, kitap) çifti: yüklenene kadar "bulunamadı" mesajı çakılmasın.
    val loaded by produceState<Pair<Boolean, BookEntity?>>(false to null, bookId) { value = true to repo.get(bookId) }
    val book = loaded.second
    when {
        !loaded.first -> Unit
        book?.documentFormat?.engine == Engine.PAGE -> PageReaderScreen(bookId, onBack)
        book?.documentFormat?.engine == Engine.REFLOW -> ReflowReaderScreen(bookId, onBack)
        else -> ReaderPlaceholderScreen(book, onBack)
    }
}

/** Kitap bulunamadığında ya da biçim tanınmadığında gösterilen ekran. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderPlaceholderScreen(book: BookEntity?, onBack: () -> Unit) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val b = book
            val text = when {
                b == null -> "Dosya bulunamadı."
                else -> "Bu biçim desteklenmiyor: ${b.documentFormat?.label ?: b.format}"
            }
            Text(text)
        }
    }
}
