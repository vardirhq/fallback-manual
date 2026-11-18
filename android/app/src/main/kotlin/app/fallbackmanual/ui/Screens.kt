package app.fallbackmanual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fallbackmanual.Bookmarks
import app.fallbackmanual.InstallChannel
import app.fallbackmanual.InstallSources
import app.fallbackmanual.KnowledgeStore
import app.fallbackmanual.core.Article
import app.fallbackmanual.core.ArticleSummary
import app.fallbackmanual.core.Category
import app.fallbackmanual.core.KnowledgeBase
import app.fallbackmanual.core.Searcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalStore = staticCompositionLocalOf<KnowledgeStore> { error("no KnowledgeStore") }
val LocalBookmarks = staticCompositionLocalOf<Bookmarks> { error("no Bookmarks") }

/** Loads data from the knowledge base off the main thread. Null while loading. */
@Composable
fun <T> load(vararg keys: Any?, block: (KnowledgeBase) -> T): State<Result<T>?> {
    val store = LocalStore.current
    return produceState<Result<T>?>(null, *keys) {
        val result = runCatching { store.use(block) }
        // Publish on the main thread whatever context this coroutine resumed in, so the change is
        // always picked up by the next recomposition.
        withContext(Dispatchers.Main.immediate) { value = result }
    }
}

@Composable
internal fun <T> Loaded(state: State<Result<T>?>, content: @Composable (T) -> Unit) {
    val r = state.value
    when {
        r == null -> Loading()
        r.isFailure -> LoadFailed(r.exceptionOrNull()!!)
        else -> content(r.getOrThrow())
    }
}

private val Gutter = PaddingValues(horizontal = 20.dp)

/** A screen's big title, under its bar. */
@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = fm.ink,
        modifier = Modifier.padding(Gutter).padding(bottom = 6.dp).semantics { heading() })
}

// -- Home ---------------------------------------------------------------------

private data class HomeData(
    val release: Boolean,
    val emergency: List<ArticleSummary>,
    val categories: List<Category>,
    val saved: List<ArticleSummary>,
)

@Composable
fun HomeScreen(go: (String) -> Unit, bottomBar: @Composable () -> Unit) {
    val ids = LocalBookmarks.current.ids
    val data = load(ids) { kb -> HomeData(kb.info.releaseBuild, kb.highRiskArticles(), kb.categories(), kb.summaries(ids)) }
    val list = rememberLazyListState()
    Scaffold(containerColor = fm.bg, bottomBar = bottomBar) { pad ->
        Loaded(data) { d ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LogoMark()
                        Text("FALLBACK MANUAL", style = MaterialTheme.typography.titleLarge, color = fm.ink,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                        IconButton(onClick = { go("about") }) {
                            Icon(FmIcons.stroke(FmIcons.INFO), contentDescription = "About", tint = fm.muted)
                        }
                    }
                }
                if (!d.release) item { DraftStrip(Modifier.padding(Gutter).padding(top = 4.dp)) }
                item {
                    Column(Modifier.padding(Gutter).padding(top = 18.dp)) {
                        Text("What's happening?", style = MaterialTheme.typography.headlineLarge, color = fm.ink,
                            modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                        SearchBox { go("search/$it") }
                    }
                }
                item { SectionLabel("Emergency", Modifier.padding(Gutter), accent = true) }
                items(d.emergency.chunked(2)) { pair ->
                    // Both tiles in a row take the height of the taller one.
                    Row(
                        Modifier.padding(Gutter).padding(bottom = 10.dp).height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { a -> EmergencyTile(a, Modifier.weight(1f).fillMaxHeight()) { go("article/${a.id}") } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (d.saved.isNotEmpty()) {
                    item { SectionLabel("Saved", Modifier.padding(Gutter)) }
                    items(d.saved, key = { "saved-" + it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
                }
                item { SectionLabel("Manual", Modifier.padding(Gutter)) }
                items(d.categories, key = { "cat-" + it.id }) { c -> CategoryRow(c) { go("category/${c.id}") } }
            }
        }
    }
}

// -- Manual (tab) and categories ----------------------------------------------

@Composable
fun ManualScreen(go: (String) -> Unit, bottomBar: @Composable () -> Unit) {
    val data = load(Unit) { kb -> kb.categories() }
    Scaffold(containerColor = fm.bg, topBar = { Bar("Offline manual", null) }, bottomBar = bottomBar) { pad ->
        Loaded(data) { cats ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { ScreenTitle("Manual") }
                items(cats, key = { it.id }) { c -> CategoryRow(c) { go("category/${c.id}") } }
            }
        }
    }
}

@Composable
fun CategoryScreen(id: String, go: (String) -> Unit, back: () -> Unit) {
    val data = load(id) { kb -> kb.categories().firstOrNull { it.id == id } to kb.articles(id) }
    val list = rememberLazyListState()
    val code = data.value?.getOrNull()?.first?.code
    Scaffold(containerColor = fm.bg, topBar = { Bar(code?.let { "$it · manual" } ?: "", back) }) { pad ->
        Loaded(data) { (cat, articles) ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = list) {
                item { ScreenTitle(cat?.title ?: id) }
                items(articles, key = { it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
            }
        }
    }
}

// -- Article ------------------------------------------------------------------

@Composable
fun ArticleScreen(id: String, go: (String) -> Unit, back: () -> Unit) {
    val bookmarks = LocalBookmarks.current
    val sheet = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() }),
    ) { mutableStateListOf<String>() }
    val data = load(id) { kb -> kb.article(id) }
    val saved = id in bookmarks.ids
    val scroll = rememberScrollState()
    val article = data.value?.getOrNull()
    Scaffold(containerColor = fm.bg, topBar = {
        Bar(article?.let { "${it.code} · ${it.categoryTitle}" } ?: "", back) {
            IconButton(onClick = { bookmarks.toggle(id) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (saved) FmIcons.fill(FmIcons.BOOKMARK) else FmIcons.stroke(FmIcons.BOOKMARK),
                    contentDescription = if (saved) "Remove from saved" else "Save article",
                    tint = if (saved) fm.accentText else fm.ink,
                )
            }
        }
    }) { pad ->
        Loaded(data) { a ->
            if (a == null) {
                LoadFailed(IllegalStateException("Article '$id' was not found."))
                return@Loaded
            }
            ArticleContent(
                a,
                onLink = { sheet.add(it) },
                onStepByStep = { go("steps/${a.id}") },
                modifier = Modifier.fillMaxSize().padding(pad).verticalScroll(scroll).padding(Gutter).padding(bottom = 40.dp),
            )
        }
    }
    // Linked articles open in a sheet on top, so the reader keeps their place here.
    ArticleSheet(sheet, fromTitle = article?.title ?: "", openFull = { go("article/$it") })
}

private const val CHEVRON_UP = "M5 15l7-7 7 7"

/** An article's full content. Shared by the article screen and the linked-article sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleContent(a: Article, onLink: (String) -> Unit, modifier: Modifier = Modifier, onStepByStep: (() -> Unit)? = null) {
    val steps = remember(a.bodyMarkdown) { stepsOf(a.bodyMarkdown).size }
    var sourcesOpen by rememberSaveable(a.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(a.title, style = MaterialTheme.typography.displaySmall, color = fm.ink,
            modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!a.reviewed) Chip("Draft · not reviewed", fm.draftBg, fm.draftInk)
            Chip("$steps steps · v${a.version}", fm.chipBg, fm.chipInk)
        }
        if (a.highRisk) CallBlock(Modifier.padding(top = 4.dp))
        if (onStepByStep != null && steps >= 2) BigButton("Step by step", icon = FmIcons.PLAY, onClick = onStepByStep)
        MarkdownBody(a.bodyMarkdown, onLink = onLink)

        // Sources and review status matter, but are noise in an emergency: folded away by default.
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (sourcesOpen) "Hide sources" else "Show sources") {
                    sourcesOpen = !sourcesOpen
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SOURCES & REVIEW", style = MaterialTheme.typography.labelMedium, color = fm.forest, modifier = Modifier.weight(1f))
            Icon(FmIcons.stroke(if (sourcesOpen) CHEVRON_UP else FmIcons.CHEVRON_DOWN),
                contentDescription = null, tint = fm.forest, modifier = Modifier.size(20.dp))
        }
        if (sourcesOpen) {
            a.sources.forEach { s ->
                Text(
                    "${s.title}, ${s.publisher}" + if (!s.checked) " (not yet checked against the original)" else "",
                    style = MaterialTheme.typography.bodyMedium, color = fm.ink,
                )
            }
            val reviewed = a.review.reviewer?.let { "Reviewed by $it on ${a.review.date}" } ?: "Not reviewed"
            Text("${a.code} · version ${a.version} · $reviewed · ${a.license}",
                style = MaterialTheme.typography.labelSmall, color = fm.muted)
        }
    }
}

// -- Search -------------------------------------------------------------------

private data class SearchData(
    val result: Searcher.Result,
    val summaries: Map<String, ArticleSummary>,
    val categoryTitles: Map<String, String>,
)

@Composable
fun SearchScreen(query: String, go: (String) -> Unit, replace: (String) -> Unit, home: () -> Unit, back: () -> Unit) {
    val data = load(query) { kb ->
        val r = kb.searcher.search(query, limit = 5)
        SearchData(r, r.matches.mapNotNull { kb.summary(it.id) }.associateBy { it.id },
            kb.categories().associate { it.id to it.title })
    }
    val list = rememberLazyListState()
    Scaffold(containerColor = fm.bg, topBar = { Bar("Search · offline", back) }) { pad ->
        Loaded(data) { d ->
            val r = d.result
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Column(Modifier.padding(Gutter)) { SearchBox(query) { replace("search/$it") } } }
                if (r.corrections.isNotEmpty()) item {
                    Text("Searched for: " + r.corrections.values.joinToString(", "), style = MaterialTheme.typography.bodyMedium,
                        color = fm.muted, modifier = Modifier.padding(Gutter).padding(top = 8.dp))
                }
                if (r.confident) {
                    val best = r.matches.first()
                    item { SectionLabel("Best match", Modifier.padding(Gutter).padding(top = 6.dp), accent = true) }
                    d.summaries[best.id]?.let { s ->
                        item { BestMatchCard(s, d.categoryTitles[s.category] ?: s.category, best.matched) { go("article/${s.id}") } }
                    }
                    val rest = r.matches.drop(1).filter { it.coverage >= 0.5 }.mapNotNull { d.summaries[it.id] }
                    if (rest.isNotEmpty()) {
                        item { SectionLabel("Also related", Modifier.padding(Gutter)) }
                        items(rest, key = { it.id }) { s -> ArticleRow(s) { go("article/${s.id}") } }
                    }
                } else {
                    item {
                        Callout(
                            CalloutKind.Info,
                            "No reviewed article matches this question",
                            "Try other words, or browse the manual.",
                            Modifier.padding(Gutter).padding(top = 16.dp),
                        )
                    }
                    val closest = r.matches.mapNotNull { d.summaries[it.id] }
                    if (closest.isNotEmpty()) {
                        item { SectionLabel("Closest (may not be relevant)", Modifier.padding(Gutter)) }
                        items(closest, key = { it.id }) { s -> ArticleRow(s) { go("article/${s.id}") } }
                    }
                }
                item {
                    Column(Modifier.padding(Gutter).padding(top = 22.dp)) {
                        Text("NOT WHAT YOU NEED?", style = MaterialTheme.typography.labelMedium, color = fm.forest)
                        Text(
                            "This app only shows instructions from its own articles. When nothing fits, it says so instead of guessing.",
                            style = MaterialTheme.typography.bodyMedium, color = fm.muted,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        )
                        OutlineButton("Browse the manual", onClick = home)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BestMatchCard(s: ArticleSummary, categoryTitle: String, matched: List<String>, onClick: () -> Unit) {
    Column(
        Modifier.padding(Gutter).fillMaxWidth().clip(Radius).background(fm.orange).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            s.iconPath?.let { Icon(FmIcons.stroke(it), contentDescription = null, tint = fm.onOrange, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Text("${s.code} · $categoryTitle".uppercase(), style = MaterialTheme.typography.labelSmall, color = fm.onOrange)
        }
        Text(s.title, style = MaterialTheme.typography.headlineMedium, color = fm.onOrange)
        Text("For when ${s.lead.replaceFirstChar { it.lowercase() }}.", style = MaterialTheme.typography.bodyLarge, color = fm.onOrange)
        if (matched.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                matched.forEachIndexed { i, w -> Chip(if (i == 0) "Matched: $w" else w, Color(0x1F16181B), fm.onOrange) }
            }
        }
    }
}

// -- Saved (tab) --------------------------------------------------------------

@Composable
fun SavedScreen(go: (String) -> Unit, bottomBar: @Composable () -> Unit) {
    val ids = LocalBookmarks.current.ids
    val data = load(ids) { kb -> kb.summaries(ids) }
    Scaffold(containerColor = fm.bg, topBar = { Bar("On this phone", null) }, bottomBar = bottomBar) { pad ->
        Loaded(data) { list ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { ScreenTitle("Saved") }
                if (list.isEmpty()) item {
                    Text(
                        "Nothing saved yet. Tap the bookmark on an article to keep it here for quick access.",
                        style = MaterialTheme.typography.bodyLarge, color = fm.muted,
                        modifier = Modifier.padding(Gutter).padding(top = 8.dp),
                    )
                }
                items(list, key = { it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
            }
        }
    }
}

// -- About --------------------------------------------------------------------

@Composable
fun AboutScreen(back: () -> Unit) {
    val data = load(Unit) { kb -> kb.info }
    Scaffold(containerColor = fm.bg, topBar = { Bar("About", back) }) { pad ->
        Loaded(data) { info ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(Gutter).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(44)
                    Text("Fallback Manual", style = MaterialTheme.typography.headlineLarge, color = fm.ink,
                        modifier = Modifier.padding(start = 14.dp))
                }
                Text("Knowledge when everything else fails.", style = MaterialTheme.typography.bodyLarge, color = fm.ink)
                Callout(
                    CalloutKind.Info,
                    "Not a substitute for professional help",
                    "This app does not replace emergency services, medical professionals or training. Contact them whenever you can.",
                )
                Text(
                    "Works fully offline. The app has no internet permission, no accounts, no ads and no tracking. " +
                        "Your searches and saved articles never leave this phone.",
                    style = MaterialTheme.typography.bodyLarge, color = fm.ink,
                )
                SectionTitle("Signing fingerprint")
                Text(LocalSharing.current.signingFingerprint() ?: "Unavailable", style = MaterialTheme.typography.labelSmall, color = fm.ink)
                Text(
                    "A copy received from another phone is genuine if it shows the same fingerprint as the sender's.",
                    style = MaterialTheme.typography.bodySmall, color = fm.muted,
                )
                SectionTitle("Content")
                Text(
                    "${info.articleCount} articles · content ${info.contentHash} · " +
                        if (info.releaseBuild) "release build" else "development build (unreviewed drafts)",
                    style = MaterialTheme.typography.bodyMedium, color = fm.ink,
                )
                InstallationSection()
                SectionTitle("Fonts")
                Text("Barlow and JetBrains Mono, under the SIL Open Font License 1.1.",
                    style = MaterialTheme.typography.bodyMedium, color = fm.ink)
            }
        }
    }
}

// -- Installation (part of About) ---------------------------------------------

private const val RELEASES = "github.com/vardirhq/fallback-manual/releases"

@Composable
private fun InstallationSection() {
    val context = LocalContext.current
    val source = remember { InstallSources.detect(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
    val (where, update) = when (source.channel) {
        InstallChannel.PlayStore -> "Installed from Google Play." to "Updates come through Google Play."
        InstallChannel.FDroid -> "Installed from F-Droid." to "Updates come through F-Droid."
        InstallChannel.ApkFile -> "Installed from an app file (APK)." to
            "This app never updates itself. To update, install a newer APK from $RELEASES, " +
            "or get one from someone with a newer version using Share this app."
        InstallChannel.Unknown -> "Android didn't record where this app came from." to
            "This app never updates itself. To update, install a newer APK from $RELEASES."
    }
    SectionTitle("Installation")
    Text("Version $version. $where", style = MaterialTheme.typography.bodyMedium, color = fm.ink)
    Text(update, style = MaterialTheme.typography.bodyMedium, color = fm.ink)
    Text("The app has no internet access and never checks for updates.",
        style = MaterialTheme.typography.bodySmall, color = fm.muted)
}
