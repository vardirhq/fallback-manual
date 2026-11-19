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
        "SELECT c.id, c.title, count(a.id), c.code, i.path FROM categories c " +
            "LEFT JOIN articles a ON a.category = c.id LEFT JOIN icons i ON i.name = c.icon " +
            "GROUP BY c.id ORDER BY c.position",
    ) { Category(it.getText(0), it.getText(1), it.getLong(2).toInt(), it.getText(3), it.textOrNull(4)) }
        .filter { includeEmpty || it.articleCount > 0 }

    fun articles(category: String): List<ArticleSummary> =
        summaries("WHERE a.category = ? ORDER BY a.code", category)

    /** The home screen's short list of act-now topics (articles with emergency: true). */
    fun emergencyArticles(): List<ArticleSummary> = summaries("WHERE a.emergency = 1 ORDER BY a.title")

    fun summaries(ids: Collection<String>): List<ArticleSummary> {
        val wanted = ids.toSet()
        return summaries("ORDER BY a.title").filter { it.id in wanted }
    }

    fun summary(id: String): ArticleSummary? = summaries("WHERE a.id = ?", id).firstOrNull()

    fun article(id: String): Article? = query(
        "SELECT a.id, a.title, a.category, a.status, a.version, a.high_risk, a.license, a.body_md, a.reviewer, " +
            "a.review_date, a.review_by, a.code, i.path, a.rhythm_bpm, a.rhythm_from, a.rhythm_to, c.title " +
            "FROM articles a LEFT JOIN icons i ON i.name = a.icon LEFT JOIN categories c ON c.id = a.category " +
            "WHERE a.id = ?",
        id,
    ) {
        Article(
            id = it.getText(0),
            title = it.getText(1),
            category = it.getText(2),
            categoryTitle = it.textOrNull(16) ?: it.getText(2),
            reviewed = it.getText(3) == "reviewed",
            version = it.getLong(4).toInt(),
            highRisk = it.getLong(5) != 0L,
            license = it.getText(6),
            bodyMarkdown = it.getText(7),
            sources = emptyList(),
            review = Review(it.textOrNull(8), it.textOrNull(9), it.textOrNull(10)),
            code = it.getText(11),
            iconPath = it.textOrNull(12),
            rhythm = if (it.isNull(13)) null else Rhythm(it.getLong(13).toInt(), it.getLong(14).toInt(), it.getLong(15).toInt()),
        )
    }.firstOrNull()?.let { a ->
        a.copy(sources = query(
            "SELECT title, publisher, url, checked FROM sources WHERE article_id = ? ORDER BY position", id,
        ) { Source(it.getText(0), it.getText(1), it.textOrNull(2), it.getLong(3) != 0L) })
    }

    /** A picture used in an article, or null if there is none with that id. */
    fun image(id: String): ArticleImage? = query(
        "SELECT mime, data, license, creator, source FROM images WHERE id = ?", id,
    ) { ArticleImage(id, it.getText(0), it.getBlob(1), it.getText(2), it.getText(3), it.getText(4)) }.firstOrNull()

    fun categoryTitle(id: String): String =
        query("SELECT title FROM categories WHERE id = ?", id) { it.getText(0) }.firstOrNull() ?: id

    private fun summaries(where: String, vararg args: String): List<ArticleSummary> = query(
        "SELECT a.id, a.title, a.category, a.high_risk, a.status, a.code, i.path, a.lead " +
            "FROM articles a LEFT JOIN icons i ON i.name = a.icon $where",
        *args,
    ) {
        ArticleSummary(
            it.getText(0), it.getText(1), it.getText(2), it.getLong(3) != 0L, it.getText(4) == "reviewed",
            it.getText(5), it.textOrNull(6), it.getText(7),
        )
    }

    private fun <T> query(sql: String, vararg args: String, row: (SQLiteStatement) -> T): List<T> =
        connection.query(sql, *args, row = row)

    override fun close() = connection.close()

    companion object {
        const val SUPPORTED_SCHEMA = 4

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
