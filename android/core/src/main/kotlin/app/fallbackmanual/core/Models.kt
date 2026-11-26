package app.fallbackmanual.core

/** [iconPath] is SVG path data on a 24×24 grid, drawn as strokes (from knowledge/icons.yaml). */
data class Category(
    val id: String,
    val title: String,
    val articleCount: Int,
    val code: String,
    val iconPath: String?,
)

data class ArticleSummary(
    val id: String,
    val title: String,
    val category: String,
    val highRisk: Boolean,
    val reviewed: Boolean,
    /** Reference code such as FA-02. */
    val code: String,
    val iconPath: String?,
    /** The article's first "situation": a one-line description of when it applies. */
    val lead: String,
    /** A few words for the home screen tile, when the title is too long to read at a glance there. */
    val shortTitle: String? = null,
)

data class Source(val title: String, val publisher: String, val url: String?, val checked: Boolean)

data class Review(val reviewer: String?, val date: String?, val reviewBy: String?)

/** A beat to follow during some steps, e.g. chest compressions in CPR. Steps are 1-based. */
data class Rhythm(val bpm: Int, val fromStep: Int, val toStep: Int) {
    fun covers(step: Int) = step in fromStep..toStep
}

data class Article(
    val id: String,
    val title: String,
    val category: String,
    val categoryTitle: String,
    val reviewed: Boolean,
    val version: Int,
    val highRisk: Boolean,
    /** On the home screen's act-now list, and opens with "call emergency services first". */
    val emergency: Boolean,
    val license: String,
    val bodyMarkdown: String,
    val sources: List<Source>,
    val review: Review,
    val code: String,
    val iconPath: String?,
    val rhythm: Rhythm?,
)

data class KnowledgeInfo(
    val schemaVersion: Int,
    val contentHash: String,
    val releaseBuild: Boolean,
    val articleCount: Int,
)

class ArticleImage(
    val id: String,
    val mime: String,
    val data: ByteArray,
    val license: String,
    val creator: String,
    val source: String,
)
