package app.fallbackmanual.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fallbackmanual.Bookmarks
import app.fallbackmanual.KnowledgeStore
import app.fallbackmanual.core.KnowledgeBase

val LocalStore = staticCompositionLocalOf<KnowledgeStore> { error("no KnowledgeStore") }
val LocalBookmarks = staticCompositionLocalOf<Bookmarks> { error("no Bookmarks") }

/** Loads data from the knowledge base off the main thread. Null while loading. */
@Composable
fun <T> load(vararg keys: Any?, block: (KnowledgeBase) -> T): State<Result<T>?> {
    val store = LocalStore.current
    return produceState<Result<T>?>(null, *keys) { value = runCatching { store.use(block) } }
}

@Composable
private fun <T> Loaded(state: State<Result<T>?>, content: @Composable (T) -> Unit) {
    val r = state.value
    when {
        r == null -> Loading()
        r.isFailure -> LoadFailed(r.exceptionOrNull()!!)
        else -> content(r.getOrThrow())
    }
}

private val Gutter = PaddingValues(horizontal = 16.dp)

// -- Home ---------------------------------------------------------------------

private data class HomeData(
    val release: Boolean,
    val emergency: List<app.fallbackmanual.core.ArticleSummary>,
    val categories: List<app.fallbackmanual.core.Category>,
    val saved: List<app.fallbackmanual.core.ArticleSummary>,
)

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val bookmarks = LocalBookmarks.current
    val ids = bookmarks.ids
    val data = load(ids) { kb -> HomeData(kb.info.releaseBuild, kb.highRiskArticles(), kb.categories(), kb.summaries(ids)) }
    Scaffold(topBar = {
        Bar("Fallback Manual", onBack = null) {
            IconButton(onClick = { go("saved") }) { Icon(Icons.Filled.Star, contentDescription = "Saved articles") }
            IconButton(onClick = { go("about") }) { Icon(Icons.Filled.Info, contentDescription = "About") }
        }
    }) { pad ->
        Loaded(data) { d ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
                if (!d.release) item {
                    Callout(
                        CalloutKind.Caution,
                        "Development build",
                        "Articles are unreviewed drafts. Do not rely on them in a real emergency.",
                        Modifier.padding(Gutter).padding(bottom = 12.dp),
                    )
                }
                item { Column(Modifier.padding(Gutter)) { SearchBox { go("search/$it") } } }
                item { SectionTitle("Emergency", Modifier.padding(Gutter)) }
                items(d.emergency.chunked(2)) { pair ->
                    Row(Modifier.padding(Gutter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { a ->
                            Card(
                                onClick = { go("article/${a.id}") },
                                modifier = Modifier.weight(1f).heightIn(min = 84.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(14.dp))
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (d.saved.isNotEmpty()) {
                    item { SectionTitle("Saved", Modifier.padding(Gutter)) }
                    items(d.saved, key = { "saved-" + it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
                }
                item { SectionTitle("Manual", Modifier.padding(Gutter)) }
                items(d.categories, key = { "cat-" + it.id }) { c ->
                    ArticleRow(
                        app.fallbackmanual.core.ArticleSummary(c.id, c.title, c.id, highRisk = false, reviewed = true),
                        supporting = if (c.articleCount == 1) "1 article" else "${c.articleCount} articles",
                    ) { go("category/${c.id}") }
                }
            }
        }
    }
}

// -- Category -----------------------------------------------------------------

@Composable
fun CategoryScreen(id: String, go: (String) -> Unit, back: () -> Unit) {
    val data = load(id) { kb -> kb.categoryTitle(id) to kb.articles(id) }
    val title = data.value?.getOrNull()?.first ?: ""
    Scaffold(topBar = { Bar(title, back) }) { pad ->
        Loaded(data) { (_, articles) ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(articles, key = { it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
            }
        }
    }
}

// -- Article ------------------------------------------------------------------

@Composable
fun ArticleScreen(id: String, back: () -> Unit) {
    val bookmarks = LocalBookmarks.current
    val data = load(id) { kb -> kb.article(id)?.let { it to kb.categoryTitle(it.category) } }
    val saved = id in bookmarks.ids
    Scaffold(topBar = {
        Bar(data.value?.getOrNull()?.second ?: "", back) {
            IconButton(onClick = { bookmarks.toggle(id) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (saved) "Remove from saved" else "Save article",
                    tint = if (saved) Color(0xFFE0A100) else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }) { pad ->
        Loaded(data) { pair ->
            if (pair == null) {
                LoadFailed(IllegalStateException("Article '$id' was not found."))
                return@Loaded
            }
            val a = pair.first
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(Gutter).padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(a.title, style = MaterialTheme.typography.headlineMedium)
                if (a.highRisk) Callout(CalloutKind.Emergency, "If you can, call emergency services now", "Then follow these steps while you wait.")
                if (!a.reviewed) Callout(
                    CalloutKind.Caution,
                    "Unreviewed draft",
                    "No qualified person has checked this article yet. Do not rely on it.",
                )
                MarkdownBody(a.bodyMarkdown)
                HorizontalDivider(Modifier.padding(top = 16.dp))
                SectionTitle("Sources")
                a.sources.forEach { s ->
                    Text(
                        "${s.title}, ${s.publisher}" + if (!s.checked) " (not yet checked against the original)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val reviewed = a.review.reviewer?.let { "Reviewed by $it on ${a.review.date}" } ?: "Not reviewed"
                Text(
                    "Version ${a.version} · $reviewed · ${a.license}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// -- Search -------------------------------------------------------------------

@Composable
fun SearchScreen(query: String, go: (String) -> Unit, replace: (String) -> Unit, home: () -> Unit, back: () -> Unit) {
    val data = load(query) { kb -> kb.searcher.search(query, limit = 5) }
    Scaffold(topBar = { Bar("Search", back) }) { pad ->
        Loaded(data) { r ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Column(Modifier.padding(Gutter)) { SearchBox(query) { replace("search/$it") } } }
                if (r.corrections.isNotEmpty()) item {
                    Text(
                        "Searched for: " + r.corrections.values.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Gutter),
                    )
                }
                if (r.confident) {
                    item { SectionTitle("Best match", Modifier.padding(Gutter)) }
                    item { ArticleRow(r.matches.first().summary()) { go("article/${r.matches.first().id}") } }
                    // Only show genuinely related articles, not every weak keyword overlap.
                    val rest = r.matches.drop(1).filter { it.coverage >= 0.5 }
                    if (rest.isNotEmpty()) {
                        item { SectionTitle("Also related", Modifier.padding(Gutter)) }
                        items(rest, key = { it.id }) { m -> ArticleRow(m.summary()) { go("article/${m.id}") } }
                    }
                } else {
                    item {
                        Callout(
                            CalloutKind.Info,
                            "No reviewed article matches this question",
                            "Try other words, or browse the manual. This app only shows instructions from its own articles; it never makes up an answer.",
                            Modifier.padding(Gutter).padding(top = 12.dp),
                        )
                    }
                    if (r.matches.isNotEmpty()) {
                        item { SectionTitle("Closest articles (may not be relevant)", Modifier.padding(Gutter)) }
                        items(r.matches, key = { it.id }) { m -> ArticleRow(m.summary()) { go("article/${m.id}") } }
                    }
                    item {
                        OutlinedButton(onClick = home, modifier = Modifier.padding(Gutter).padding(top = 16.dp).fillMaxWidth().heightIn(min = 52.dp)) {
                            Text("Browse the manual")
                        }
                    }
                }
            }
        }
    }
}

// Search results don't carry review status; the article screen shows it.
private fun app.fallbackmanual.core.Searcher.Match.summary() =
    app.fallbackmanual.core.ArticleSummary(id, title, category, highRisk, reviewed = true)

// -- Saved --------------------------------------------------------------------

@Composable
fun SavedScreen(go: (String) -> Unit, back: () -> Unit) {
    val ids = LocalBookmarks.current.ids
    val data = load(ids) { kb -> kb.summaries(ids) }
    Scaffold(topBar = { Bar("Saved", back) }) { pad ->
        Loaded(data) { list ->
            if (list.isEmpty()) {
                Text(
                    "Nothing saved yet. Tap the star on an article to keep it here for quick access.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(pad).padding(32.dp).fillMaxWidth(),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                    items(list, key = { it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
                }
            }
        }
    }
}

// -- About --------------------------------------------------------------------

@Composable
fun AboutScreen(back: () -> Unit) {
    val data = load(Unit) { kb -> kb.info }
    Scaffold(topBar = { Bar("About", back) }) { pad ->
        Loaded(data) { info ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(Gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Fallback Manual", style = MaterialTheme.typography.headlineMedium)
                Text("Knowledge when everything else fails.", style = MaterialTheme.typography.bodyLarge)
                Callout(
                    CalloutKind.Info,
                    "Not a substitute for professional help",
                    "This app does not replace emergency services, medical professionals or training. Contact them whenever you can.",
                )
                Text(
                    "Works fully offline. The app has no internet permission, no accounts, no ads and no tracking. " +
                        "Your searches and saved articles never leave this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SectionTitle("Content")
                Text(
                    "${info.articleCount} articles · content ${info.contentHash} · " +
                        if (info.releaseBuild) "release build" else "development build (unreviewed drafts)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
