package app.fallbackmanual.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY

/**
 * Read-only access to the knowledge database built by `tools/build_kb.py`.
 *
 * Not thread-safe: use one instance from one thread (or a single-threaded dispatcher).
 */
class KnowledgeBase private constructor(private val connection: SQLiteConnection) : AutoCloseable {

    val info: KnowledgeInfo = run {
        val meta = query("SELECT key, value FROM meta") { it.getText(0) to it.getText(1) }.toMap()
        val schema = meta["schema_version"]?.toIntOrNull() ?: 0
        require(schema == SUPPORTED_SCHEMA) { "Unsupported knowledge schema $schema (expected $SUPPORTED_SCHEMA)" }
        KnowledgeInfo(
            schemaVersion = schema,
            contentHash = meta["content_hash"].orEmpty(),
            releaseBuild = meta["release"] == "1",
            articleCount = meta["article_count"]?.toIntOrNull() ?: 0,
        )
    }

    val searcher: Searcher by lazy { Searcher(connection) }

    fun categories(includeEmpty: Boolean = false): List<Category> = query(
        "SELECT c.id, c.title, count(a.id) FROM categories c LEFT JOIN articles a ON a.category = c.id " +
            "GROUP BY c.id ORDER BY c.position",
    ) { Category(it.getText(0), it.getText(1), it.getLong(2).toInt()) }
        .filter { includeEmpty || it.articleCount > 0 }

    fun articles(category: String): List<ArticleSummary> =
        summaries("WHERE category = ? ORDER BY title", category)

    fun highRiskArticles(): List<ArticleSummary> = summaries("WHERE high_risk = 1 ORDER BY title")

    fun summaries(ids: Collection<String>): List<ArticleSummary> {
        val wanted = ids.toSet()
        return summaries("ORDER BY title").filter { it.id in wanted }
    }

    fun article(id: String): Article? = query(
        "SELECT id, title, category, status, version, high_risk, license, body_md, reviewer, review_date, review_by " +
            "FROM articles WHERE id = ?",
        id,
    ) {
        Article(
            id = it.getText(0),
            title = it.getText(1),
            category = it.getText(2),
            reviewed = it.getText(3) == "reviewed",
            version = it.getLong(4).toInt(),
            highRisk = it.getLong(5) != 0L,
            license = it.getText(6),
            bodyMarkdown = it.getText(7),
            sources = emptyList(),
            review = Review(it.textOrNull(8), it.textOrNull(9), it.textOrNull(10)),
        )
    }.firstOrNull()?.let { a ->
        a.copy(sources = query(
            "SELECT title, publisher, url, checked FROM sources WHERE article_id = ? ORDER BY position", id,
        ) { Source(it.getText(0), it.getText(1), it.textOrNull(2), it.getLong(3) != 0L) })
    }

    fun categoryTitle(id: String): String =
        query("SELECT title FROM categories WHERE id = ?", id) { it.getText(0) }.firstOrNull() ?: id

    private fun summaries(where: String, vararg args: String): List<ArticleSummary> =
        query("SELECT id, title, category, high_risk, status FROM articles $where", *args) {
            ArticleSummary(it.getText(0), it.getText(1), it.getText(2), it.getLong(3) != 0L, it.getText(4) == "reviewed")
        }

    private fun <T> query(sql: String, vararg args: String, row: (SQLiteStatement) -> T): List<T> =
        connection.query(sql, *args, row = row)

    override fun close() = connection.close()

    companion object {
        const val SUPPORTED_SCHEMA = 1

        fun open(path: String): KnowledgeBase =
            KnowledgeBase(BundledSQLiteDriver().open(path, SQLITE_OPEN_READONLY))
    }
}

internal fun SQLiteStatement.textOrNull(i: Int): String? = if (isNull(i)) null else getText(i)

internal fun <T> SQLiteConnection.query(sql: String, vararg args: String, row: (SQLiteStatement) -> T): List<T> =
    prepare(sql).use { st ->
        args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
        buildList { while (st.step()) add(row(st)) }
    }
