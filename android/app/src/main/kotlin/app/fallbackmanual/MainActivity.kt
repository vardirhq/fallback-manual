package app.fallbackmanual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import app.fallbackmanual.ui.AboutScreen
import app.fallbackmanual.ui.ArticleScreen
import app.fallbackmanual.ui.CategoryScreen
import app.fallbackmanual.ui.FallbackTheme
import app.fallbackmanual.ui.HomeScreen
import app.fallbackmanual.ui.LocalBookmarks
import app.fallbackmanual.ui.LocalStore
import app.fallbackmanual.ui.SavedScreen
import app.fallbackmanual.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FallbackApp
        setContent {
            FallbackTheme {
                CompositionLocalProvider(LocalStore provides app.knowledge, LocalBookmarks provides app.bookmarks) {
                    App()
                }
            }
        }
    }
}

/** Routes: home, category/<id>, article/<id>, search/<query>, saved, about. */
@Composable
fun App() {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() }),
    ) { mutableStateListOf("home") }

    val go: (String) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val replace: (String) -> Unit = { stack[stack.lastIndex] = it }
    val home: () -> Unit = { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val route = stack.last()
    val kind = route.substringBefore('/')
    val arg = route.substringAfter('/', "")
    when (kind) {
        "category" -> CategoryScreen(arg, go, back)
        "article" -> ArticleScreen(arg, back)
        "search" -> SearchScreen(arg, go, replace, home, back)
        "saved" -> SavedScreen(go, back)
        "about" -> AboutScreen(back)
        else -> HomeScreen(go)
    }
}
