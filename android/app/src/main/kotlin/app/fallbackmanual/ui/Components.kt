package app.fallbackmanual.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fallbackmanual.core.ArticleSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
fun SearchBox(initial: String = "", onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (text.isNotBlank()) {
            keyboard?.hide()
            onSearch(text.trim())
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
            }
        },
        placeholder = { Text("Describe the problem") },
        supportingText = { Text("For example: someone is shivering and confused") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submit() }),
    )
}

enum class CalloutKind { Emergency, Caution, Info }

@Composable
fun Callout(kind: CalloutKind, title: String, body: String? = null, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        CalloutKind.Emergency -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        CalloutKind.Caution -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CalloutKind.Info -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (kind != CalloutKind.Info) Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ArticleRow(a: ArticleSummary, supporting: String? = null, onClick: () -> Unit) {
    val notes = listOfNotNull(supporting, if (!a.reviewed) "Unreviewed draft" else null).joinToString(" · ")
    ListItem(
        headlineContent = { Text(a.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (notes.isNotEmpty()) ({ Text(notes) }) else null,
        leadingContent = {
            Box(
                Modifier.size(12.dp).padding(0.dp),
            ) {
                if (a.highRisk) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color(0xFFC0392B))
                    }
                }
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.heightIn(min = 64.dp).clickable(onClick = onClick),
    )
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun LoadFailed(error: Throwable) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Callout(CalloutKind.Emergency, "The manual could not be loaded", error.message ?: error.toString())
    }
}
