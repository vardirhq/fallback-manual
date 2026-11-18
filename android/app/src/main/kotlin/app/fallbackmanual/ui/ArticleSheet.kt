package app.fallbackmanual.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Shows a linked article over the current one. Links inside the sheet open in the same sheet,
 * with a back arrow to return to the previous one; closing it returns to the article underneath
 * exactly where the reader left it.
 *
 * [stack] holds the ids of the articles opened in the sheet; empty means the sheet is closed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleSheet(stack: SnapshotStateList<String>, fromTitle: String, openFull: (String) -> Unit) {
    if (stack.isEmpty()) return
    val id = stack.last()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { stack.clear() } }

    ModalBottomSheet(onDismissRequest = { stack.clear() }, sheetState = state) {
      // Stop short of the top so the article underneath stays visible: the reader hasn't left it.
      Column(Modifier.fillMaxHeight(0.9f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) {
                IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to previous article")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                "From: $fromTitle",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        key(id) {
            val data = load(id) { kb -> kb.article(id) }
            Loaded(data) { article ->
                if (article == null) {
                    LoadFailed(IllegalStateException("Article '$id' was not found."))
                    return@Loaded
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                    ArticleContent(article, onLink = { stack.add(it) })
                    OutlinedButton(
                        onClick = {
                            stack.clear()
                            openFull(id)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp),
                    ) { Text("Open as full article", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
      }
    }
}
