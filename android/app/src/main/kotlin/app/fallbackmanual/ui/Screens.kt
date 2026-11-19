package app.fallbackmanual.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fallbackmanual.Bookmarks
import app.fallbackmanual.InstallChannel
import app.fallbackmanual.InstallSources
import app.fallbackmanual.KnowledgeStore
import app.fallbackmanual.R
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
    val data = load(ids) { kb -> HomeData(kb.info.releaseBuild, kb.emergencyArticles(), kb.categories(), kb.summaries(ids)) }
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
                        Text(stringResource(R.string.app_name).uppercase(), style = MaterialTheme.typography.titleLarge, color = fm.ink,
                            modifier = Modifier.weight(1f).padding(start = 10.dp))
                        IconButton(onClick = { go("about") }) {
                            Icon(FmIcons.stroke(FmIcons.INFO), contentDescription = stringResource(R.string.about), tint = fm.muted)
                        }
                    }
                }
                if (!d.release) item { DraftStrip(Modifier.padding(Gutter).padding(top = 4.dp)) }
                item {
                    Column(Modifier.padding(Gutter).padding(top = 18.dp)) {
                        Text(stringResource(R.string.home_question), style = MaterialTheme.typography.headlineLarge, color = fm.ink,
                            modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                        SearchBox { go("search/$it") }
                    }
                }
                item { SectionLabel(stringResource(R.string.section_emergency), Modifier.padding(Gutter), accent = true) }
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
                    item { SectionLabel(stringResource(R.string.section_saved), Modifier.padding(Gutter)) }
                    items(d.saved, key = { "saved-" + it.id }) { a -> ArticleRow(a) { go("article/${a.id}") } }
                }
                item { SectionLabel(stringResource(R.string.section_manual), Modifier.padding(Gutter)) }
                items(d.categories, key = { "cat-" + it.id }) { c -> CategoryRow(c) { go("category/${c.id}") } }
            }
        }
    }
}

// -- Manual (tab) and categories ----------------------------------------------

@Composable
fun ManualScreen(go: (String) -> Unit, bottomBar: @Composable () -> Unit) {
    val data = load(Unit) { kb -> kb.categories() }
    Scaffold(containerColor = fm.bg, topBar = { Bar(stringResource(R.string.bar_offline_manual), null) }, bottomBar = bottomBar) { pad ->
        Loaded(data) { cats ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { ScreenTitle(stringResource(R.string.section_manual)) }
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
    Scaffold(containerColor = fm.bg, topBar = { Bar(code?.let { stringResource(R.string.bar_category, it) } ?: "", back) }) { pad ->
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
                    contentDescription = stringResource(if (saved) R.string.remove_from_saved else R.string.save_article),
                    tint = if (saved) fm.accentText else fm.ink,
                )
            }
        }
    }) { pad ->
        Loaded(data) { a ->
            if (a == null) {
                LoadFailed(IllegalStateException(stringResource(R.string.article_not_found, id)))
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
    val toggleLabel = stringResource(if (sourcesOpen) R.string.hide_sources else R.string.show_sources)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(a.title, style = MaterialTheme.typography.displaySmall, color = fm.ink,
            modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!a.reviewed) Chip(stringResource(R.string.chip_draft), fm.draftBg, fm.draftInk)
            Chip(pluralStringResource(R.plurals.chip_steps, steps, steps, a.version), fm.chipBg, fm.chipInk)
        }
        // Only for act-now topics: a high-risk article about preparing (generators, water) has no one to call yet.
        if (a.emergency) CallBlock(Modifier.padding(top = 4.dp))
        if (onStepByStep != null && steps >= 2) BigButton(stringResource(R.string.step_by_step), icon = FmIcons.PLAY, onClick = onStepByStep)
        MarkdownBody(a.bodyMarkdown, onLink = onLink)

        // Sources and review status matter, but are noise in an emergency: folded away by default.
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = toggleLabel) {
                    sourcesOpen = !sourcesOpen
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sources_review), style = MaterialTheme.typography.labelMedium, color = fm.forest, modifier = Modifier.weight(1f))
            Icon(FmIcons.stroke(if (sourcesOpen) CHEVRON_UP else FmIcons.CHEVRON_DOWN),
                contentDescription = null, tint = fm.forest, modifier = Modifier.size(20.dp))
        }
        if (sourcesOpen) {
            a.sources.forEach { s ->
                Text(
                    stringResource(if (s.checked) R.string.source_line else R.string.source_line_unchecked, s.title, s.publisher),
                    style = MaterialTheme.typography.bodyMedium, color = fm.ink,
                )
            }
            val reviewed = a.review.reviewer?.let { stringResource(R.string.reviewed_by, it, a.review.date ?: "") }
                ?: stringResource(R.string.not_reviewed)
            Text(stringResource(R.string.article_meta, a.code, a.version, reviewed, a.license),
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
    Scaffold(containerColor = fm.bg, topBar = { Bar(stringResource(R.string.bar_search), back) }) { pad ->
        Loaded(data) { d ->
            val r = d.result
            LazyColumn(Modifier.fillMaxSize().padding(pad), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Column(Modifier.padding(Gutter)) { SearchBox(query) { replace("search/$it") } } }
                if (r.corrections.isNotEmpty()) item {
                    Text(stringResource(R.string.searched_for, r.corrections.values.joinToString(", ")), style = MaterialTheme.typography.bodyMedium,
                        color = fm.muted, modifier = Modifier.padding(Gutter).padding(top = 8.dp))
                }
                if (r.confident) {
                    val best = r.matches.first()
                    item { SectionLabel(stringResource(R.string.best_match), Modifier.padding(Gutter).padding(top = 6.dp), accent = true) }
                    d.summaries[best.id]?.let { s ->
                        item { BestMatchCard(s, d.categoryTitles[s.category] ?: s.category, best.matched) { go("article/${s.id}") } }
                    }
                    val rest = r.matches.drop(1).filter { it.coverage >= 0.5 }.mapNotNull { d.summaries[it.id] }
                    if (rest.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.also_related), Modifier.padding(Gutter)) }
                        items(rest, key = { it.id }) { s -> ArticleRow(s) { go("article/${s.id}") } }
                    }
                } else {
                    item {
                        Callout(
                            CalloutKind.Info,
                            stringResource(R.string.no_match_title),
                            stringResource(R.string.no_match_body),
                            Modifier.padding(Gutter).padding(top = 16.dp),
                        )
                    }
                    val closest = r.matches.mapNotNull { d.summaries[it.id] }
                    if (closest.isNotEmpty()) {
                        item { SectionLabel(stringResource(R.string.closest), Modifier.padding(Gutter)) }
                        items(closest, key = { it.id }) { s -> ArticleRow(s) { go("article/${s.id}") } }
                    }
                }
                item {
                    Column(Modifier.padding(Gutter).padding(top = 22.dp)) {
                        Text(stringResource(R.string.not_what_you_need), style = MaterialTheme.typography.labelMedium, color = fm.forest)
                        Text(
                            stringResource(R.string.search_disclaimer),
                            style = MaterialTheme.typography.bodyMedium, color = fm.muted,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        )
                        OutlineButton(stringResource(R.string.browse_manual), onClick = home)
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
        Text(stringResource(R.string.best_match_lead, s.lead.replaceFirstChar { it.lowercase() }), style = MaterialTheme.typography.bodyLarge, color = fm.onOrange)
        if (matched.isNotEmpty()) {
            val firstMatched = stringResource(R.string.matched, matched.first())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                matched.forEachIndexed { i, w -> Chip(if (i == 0) firstMatched else w, Color(0x1F16181B), fm.onOrange) }
            }
        }
    }
}

// -- Saved (tab) --------------------------------------------------------------

@Composable
fun SavedScreen(go: (String) -> Unit, bottomBar: @Composable () -> Unit) {
    val ids = LocalBookmarks.current.ids
    val data = load(ids) { kb -> kb.summaries(ids) }
    Scaffold(containerColor = fm.bg, topBar = { Bar(stringResource(R.string.bar_on_this_phone), null) }, bottomBar = bottomBar) { pad ->
        Loaded(data) { list ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item { ScreenTitle(stringResource(R.string.section_saved)) }
                if (list.isEmpty()) item {
                    Text(
                        stringResource(R.string.nothing_saved),
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
    Scaffold(containerColor = fm.bg, topBar = { Bar(stringResource(R.string.about), back) }) { pad ->
        Loaded(data) { info ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(Gutter).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(44)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = fm.ink,
                        modifier = Modifier.padding(start = 14.dp))
                }
                Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge, color = fm.ink)
                Callout(
                    CalloutKind.Info,
                    stringResource(R.string.about_not_substitute_title),
                    stringResource(R.string.about_not_substitute_body),
                )
                Text(
                    stringResource(R.string.about_offline),
                    style = MaterialTheme.typography.bodyLarge, color = fm.ink,
                )
                SectionTitle(stringResource(R.string.about_fingerprint))
                Text(LocalSharing.current.signingFingerprint() ?: stringResource(R.string.unavailable), style = MaterialTheme.typography.labelSmall, color = fm.ink)
                Text(
                    stringResource(R.string.about_fingerprint_body),
                    style = MaterialTheme.typography.bodySmall, color = fm.muted,
                )
                SectionTitle(stringResource(R.string.about_content))
                Text(
                    pluralStringResource(
                        R.plurals.about_content_line, info.articleCount, info.articleCount, info.contentHash,
                        stringResource(if (info.releaseBuild) R.string.build_release else R.string.build_development),
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = fm.ink,
                )
                InstallationSection()
                SectionTitle(stringResource(R.string.about_fonts))
                Text(stringResource(R.string.about_fonts_body),
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
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    } ?: stringResource(R.string.unknown)
    val (where, update) = when (source.channel) {
        InstallChannel.PlayStore -> stringResource(R.string.installed_play) to stringResource(R.string.updates_play)
        InstallChannel.FDroid -> stringResource(R.string.installed_fdroid) to stringResource(R.string.updates_fdroid)
        InstallChannel.ApkFile -> stringResource(R.string.installed_apk) to stringResource(R.string.updates_apk, RELEASES)
        InstallChannel.Unknown -> stringResource(R.string.installed_unknown) to stringResource(R.string.updates_unknown, RELEASES)
    }
    SectionTitle(stringResource(R.string.about_installation))
    Text(stringResource(R.string.about_version, version, where), style = MaterialTheme.typography.bodyMedium, color = fm.ink)
    Text(update, style = MaterialTheme.typography.bodyMedium, color = fm.ink)
    Text(stringResource(R.string.about_no_internet),
        style = MaterialTheme.typography.bodySmall, color = fm.muted)
}
