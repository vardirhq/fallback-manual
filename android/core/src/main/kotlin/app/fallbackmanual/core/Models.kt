package app.fallbackmanual.core

data class Category(val id: String, val title: String, val articleCount: Int)

data class ArticleSummary(
    val id: String,
    val title: String,
    val category: String,
    val highRisk: Boolean,
    val reviewed: Boolean,
)

data class Source(val title: String, val publisher: String, val url: String?, val checked: Boolean)

data class Review(val reviewer: String?, val date: String?, val reviewBy: String?)

data class Article(
    val id: String,
    val title: String,
    val category: String,
    val reviewed: Boolean,
    val version: Int,
    val highRisk: Boolean,
    val license: String,
    val bodyMarkdown: String,
    val sources: List<Source>,
    val review: Review,
)

data class KnowledgeInfo(
    val schemaVersion: Int,
    val contentHash: String,
    val releaseBuild: Boolean,
    val articleCount: Int,
)
