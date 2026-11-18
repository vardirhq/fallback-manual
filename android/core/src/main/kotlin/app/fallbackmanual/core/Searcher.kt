package app.fallbackmanual.core

import androidx.sqlite.SQLiteConnection
import java.text.Normalizer
import java.util.Locale

/**
 * Layer 0 search. A line-by-line port of `retrieval/search.py`; see that file for the
 * algorithm. `SearchParityTest` checks that both return the same results, so change
 * them together.
 */
class Searcher internal constructor(private val connection: SQLiteConnection) {

    data class Concept(val words: String, val terms: List<String>, val fromSynonyms: Boolean)

    data class Match(
        val id: String,
        val title: String,
        val category: String,
        val highRisk: Boolean,
        val coverage: Double,
        val bm25: Double,
        val strongSynonymHit: Boolean,
        /** The words of the question this article matched (for showing why it was chosen). */
        val matched: List<String> = emptyList(),
    )

    data class Result(
        val query: String,
        val concepts: List<Concept>,
        val matches: List<Match>,
        val confident: Boolean,
        val corrections: Map<String, String>,
    ) {
        val best: Match? get() = if (confident) matches.firstOrNull() else null
    }

    private val stopWords: Set<String> = connection.query("SELECT word FROM stopwords") { it.getText(0) }.toSet()
    private val vocab: Set<String> = connection.query("SELECT word FROM vocab") { it.getText(0) }.toSet()
    private val correctionCandidates: List<String> = (vocab + stopWords).sorted()
    private val groups: Map<Long, List<String>>
    private val phraseGroups: Map<List<String>, List<Long>>
    private val maxPhrase: Int

    init {
        val g = LinkedHashMap<Long, MutableList<String>>()
        connection.query("SELECT group_id, term FROM synonyms ORDER BY rowid") { it.getLong(0) to it.getText(1) }
            .forEach { (gid, term) -> g.getOrPut(gid) { mutableListOf() }.add(term) }
        groups = g
        val p = LinkedHashMap<List<String>, MutableList<Long>>()
        for ((gid, terms) in g) for (t in terms) p.getOrPut(normalise(t)) { mutableListOf() }.add(gid)
        phraseGroups = p
        maxPhrase = p.keys.maxOfOrNull { it.size } ?: 1
    }

    // -- query understanding -------------------------------------------------

    private fun correct(word: String): String? {
        if (word in vocab || word in stopWords || word.length < 5 || word.isAllDigits()) return null
        val limit = if (word.length < 8) 1 else 2
        var best: String? = null
        var bestD = limit + 1
        // Candidates are sorted, so the first one at the smallest distance is the
        // alphabetically smallest, matching the Python tie-break.
        for (v in correctionCandidates) {
            if (v[0] != word[0]) continue
            val d = editDistance(word, v, limit)
            if (d < bestD) {
                best = v
                bestD = d
            }
        }
        return if (bestD <= limit) best else null
    }

    fun concepts(query: String): Pair<List<Concept>, Map<String, String>> {
        val corrections = LinkedHashMap<String, String>()
        val fixed = normalise(query).map { w -> correct(w)?.also { corrections[w] = it } ?: w }

        val out = mutableListOf<Concept>()
        var i = 0
        while (i < fixed.size) {
            var matched = false
            for (n in minOf(maxPhrase, fixed.size - i) downTo 1) {
                val phrase = fixed.subList(i, i + n)
                var gids = phraseGroups[phrase]
                if (gids == null && n == 1 && phrase[0].endsWith("s")) {
                    gids = phraseGroups[listOf(phrase[0].dropLast(1))]
                }
                if (!gids.isNullOrEmpty()) {
                    val terms = gids.flatMap { groups.getValue(it) }.toSortedSet().toList()
                    out.add(Concept(phrase.joinToString(" "), terms, true))
                    i += n
                    matched = true
                    break
                }
            }
            if (!matched) {
                val w = fixed[i]
                if (w !in stopWords && !w.isAllDigits() && w.length > 1) out.add(Concept(w, listOf(w), false))
                i += 1
            }
        }
        val merged = mutableListOf<Concept>()
        for (c in out) if (merged.none { it.terms == c.terms }) merged.add(c)
        return merged to corrections
    }

    // -- search --------------------------------------------------------------

    fun search(query: String, limit: Int = 3): Result {
        val (concepts, corrections) = concepts(query)
        if (concepts.isEmpty()) return Result(query, concepts, emptyList(), false, corrections)

        val exprs = concepts.map { ftsExpr(it.terms) }
        val anyExpr = exprs.joinToString(" OR ")
        val weights = BM25_WEIGHTS.joinToString(", ")
        val rows = connection.prepare(
            "SELECT f.rowid, a.id, a.title, a.category, a.high_risk, bm25(articles_fts, $weights) AS s " +
                "FROM articles_fts f JOIN articles a ON a.rowid = f.rowid " +
                "WHERE articles_fts MATCH ? ORDER BY s LIMIT $CANDIDATES",
        ).use { st ->
            st.bindText(1, anyExpr)
            buildList {
                while (st.step()) {
                    add(Row(st.getLong(0), st.getText(1), st.getText(2), st.getText(3), st.getLong(4) != 0L, st.getDouble(5)))
                }
            }
        }

        val matches = rows.map { r ->
            var total = 0.0
            var synHit = false
            val matched = mutableListOf<String>()
            concepts.zip(exprs).forEach { (c, e) ->
                if (hit(r.rowid, "$STRONG_COLUMNS : $e")) {
                    total += 1.0
                    synHit = synHit || c.fromSynonyms
                    matched += c.words
                } else if (hit(r.rowid, "body : $e")) {
                    total += 0.5
                    matched += c.words
                }
            }
            Match(r.id, r.title, r.category, r.highRisk, total / exprs.size, r.bm25, synHit, matched)
        }.sortedWith(compareBy<Match> { -it.coverage }.thenBy { it.bm25 })

        val top = matches.firstOrNull()
        val confident = top != null &&
            (top.coverage >= THRESHOLD || (top.strongSynonymHit && top.coverage >= SYNONYM_THRESHOLD))
        return Result(query, concepts, matches.take(limit), confident, corrections)
    }

    private data class Row(val rowid: Long, val id: String, val title: String, val category: String, val highRisk: Boolean, val bm25: Double)

    private fun hit(rowid: Long, expr: String): Boolean =
        connection.prepare("SELECT 1 FROM articles_fts WHERE articles_fts MATCH ? AND rowid = ?").use { st ->
            st.bindText(1, expr)
            st.bindLong(2, rowid)
            st.step()
        }

    companion object {
        const val THRESHOLD = 0.6
        const val SYNONYM_THRESHOLD = 0.4
        private const val CANDIDATES = 10
        private const val STRONG_COLUMNS = "{title situations keywords}"
        private val BM25_WEIGHTS = listOf("0.0", "10.0", "6.0", "4.0", "1.0") // id, title, situations, keywords, body

        private val CONTRACTION = Regex("'(s|t|re|ve|ll|d|m)\\b")
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        fun normalise(text: String): List<String> {
            var t = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('’', '\'')
            t = CONTRACTION.replace(t, "")
            return WORD.findAll(t).map { it.value }.toList()
        }

        internal fun ftsExpr(terms: List<String>): String =
            terms.map { normalise(it) }.filter { it.isNotEmpty() }
                .joinToString(" OR ", "(", ")") { "\"" + it.joinToString(" ") + "\"" }

        internal fun editDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
                }
                if (cur.min() > limit) return limit + 1
                prev = cur
            }
            return prev[b.length]
        }

        private fun String.isAllDigits() = isNotEmpty() && all { it.isDigit() }
    }
}
