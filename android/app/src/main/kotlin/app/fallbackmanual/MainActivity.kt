package app.fallbackmanual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import app.fallbackmanual.ui.AboutScreen
import app.fallbackmanual.ui.ArticleScreen
import app.fallbackmanual.ui.BottomNav
import app.fallbackmanual.ui.CategoryScreen
import app.fallbackmanual.ui.ManualScreen
import app.fallbackmanual.ui.StepModeScreen
import app.fallbackmanual.ui.Tab
import app.fallbackmanual.ui.FallbackTheme
import app.fallbackmanual.ui.HomeScreen
import app.fallbackmanual.ui.LocalBookmarks
import app.fallbackmanual.ui.LocalSharing
import app.fallbackmanual.ui.ShareScreen
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
                CompositionLocalProvider(
                    LocalStore provides app.knowledge,
                    LocalBookmarks provides app.bookmarks,
                    LocalSharing provides app.sharing,
                ) {
                    App()
                }
            }
        }
    }
}

/**
 * Routes: home, manual, saved, share (the four tabs), category/<id>, article/<id>, steps/<id>,
 * search/<query>, about. Switching tabs resets the stack to that tab, with Home underneath.
 */
@Composable
fun App() {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() }),
    ) { mutableStateListOf("home") }

    // Each back-stack entry keeps its own saved state (scroll position, open sheet), so going
    // back returns the reader to exactly where they were.
    val states = rememberSaveableStateHolder()
    fun entryKey(index: Int) = "$index:${stack[index]}"
    fun pop() {
        states.removeState(entryKey(stack.lastIndex))
        stack.removeAt(stack.lastIndex)
    }

    val go: (String) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) pop() }
    val replace: (String) -> Unit = { states.removeState(entryKey(stack.lastIndex)); stack[stack.lastIndex] = it }
    val home: () -> Unit = { while (stack.size > 1) pop() }
    val selectTab: (Tab) -> Unit = { tab ->
        home()
        if (tab != Tab.Home) go(tab.route)
    }
    val nav: @Composable (Tab) -> Unit = { current -> BottomNav(current, selectTab) }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val route = stack.last()
    val kind = route.substringBefore('/')
    val arg = route.substringAfter('/', "")
    val entry = entryKey(stack.lastIndex)
    key(entry) {
        states.SaveableStateProvider(entry) {
            when (kind) {
                "category" -> CategoryScreen(arg, go, back)
                "article" -> ArticleScreen(arg, go, back)
                "steps" -> StepModeScreen(arg, back)
                "search" -> SearchScreen(arg, go, replace, home, back)
                "manual" -> ManualScreen(go) { nav(Tab.Manual) }
                "saved" -> SavedScreen(go) { nav(Tab.Saved) }
                "share" -> ShareScreen { nav(Tab.Share) }
                "about" -> AboutScreen(back)
                else -> HomeScreen(go) { nav(Tab.Home) }
            }
        }
    }
}
