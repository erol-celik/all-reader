package com.erol.allreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.erol.allreader.ui.library.LibraryScreen
import com.erol.allreader.ui.reader.ReaderRoute
import com.erol.allreader.ui.theme.AllReaderTheme

class MainActivity : ComponentActivity() {
    /** "Birlikte aç" ile gelen, henüz açılmamış dosya. */
    private var external by mutableStateOf<Pair<Uri, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeViewIntent(intent)
        setContent {
            AllReaderTheme {
                val nav = rememberNavController()
                val container = (application as AllReaderApp).container

                LaunchedEffect(external) {
                    val (uri, mime) = external ?: return@LaunchedEffect
                    val id = try {
                        container.library.openExternal(uri, mime)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    // Anahtar işten sonra sıfırlanır; önce sıfırlamak çalışan işi iptal ederdi.
                    external = null
                    if (id == null) {
                        Toast.makeText(this@MainActivity, "Bu dosya açılamadı ya da türü desteklenmiyor", Toast.LENGTH_LONG).show()
                    } else {
                        nav.navigate("reader/$id")
                    }
                }

                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(onOpenBook = { nav.navigate("reader/${it.id}") })
                    }
                    composable(
                        "reader/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        ReaderRoute(
                            bookId = entry.arguments?.getLong("id") ?: -1L,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeViewIntent(intent)
    }

    private fun takeViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        external = data to intent.type
    }
}
