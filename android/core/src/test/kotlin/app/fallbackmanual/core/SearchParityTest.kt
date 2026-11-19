package app.fallbackmanual.core

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Checks the Kotlin search returns exactly what the Python reference implementation returns. */
class SearchParityTest {

    @Test
    fun matchesPythonReferenceForEveryQuery() {
        val lines = File(System.getProperty("expected.path")).readLines().filter { it.isNotEmpty() || false }
        assertTrue("expected results file is empty", lines.size > 50)
        val failures = mutableListOf<String>()
        for (line in lines) {
            val cols = line.split('\t')
            val query = cols[0]
            val wantConfident = cols[1] == "1"
            val wantIds = cols[2].split(',').filter { it.isNotEmpty() }
            val wantCorr = cols.getOrElse(3) { "" }.split(';').filter { it.isNotEmpty() }
            val r = kb.searcher.search(query, limit = 3)
            val gotCorr = r.corrections.entries.map { "${it.key}>${it.value}" }.sorted()
            if (r.confident != wantConfident || r.matches.map { it.id } != wantIds || gotCorr != wantCorr) {
                failures += "'$query': want $wantConfident $wantIds $wantCorr, got ${r.confident} ${r.matches.map { it.id }} $gotCorr"
            }
        }
        assertTrue("Kotlin and Python search disagree:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun editDistance() {
        assertEquals(1, Searcher.editDistance("bleding", "bleeding", 2))
        assertEquals(0, Searcher.editDistance("same", "same", 1))
        assertEquals(2, Searcher.editDistance("abc", "xyz", 1))
    }

    @Test
    fun normalise() {
        assertEquals(listOf("someone", "bleeding", "won", "stop"), Searcher.normalise("Someone’s BLEEDING!! won't stop"))
    }

    @Test
    fun readsArticles() {
        val cats = kb.categories()
        assertTrue(cats.any { it.id == "first-aid" && it.title == "First aid" && it.articleCount >= 3 })
        assertTrue(kb.highRiskArticles().any { it.id == "severe-bleeding" })
        val a = assertNotNull(kb.article("hypothermia")).let { kb.article("hypothermia")!! }
        assertEquals("Hypothermia", a.title)
        assertFalse(a.reviewed)
        assertTrue(a.bodyMarkdown.contains("## Steps"))
        assertTrue(a.sources.isNotEmpty())
        assertEquals(KnowledgeBase.SUPPORTED_SCHEMA, kb.info.schemaVersion)
        val cpr = kb.article("cpr")!!
        assertEquals("FA-02", cpr.code)
        assertEquals("First aid", cpr.categoryTitle)
        assertEquals(Rhythm(110, 9, 13), cpr.rhythm)
        assertTrue(cpr.iconPath!!.startsWith("M"))
        assertTrue(cats.first { it.id == "first-aid" }.code == "FA")
        assertTrue(kb.summary("cpr")!!.lead.isNotEmpty())
        val r = kb.searcher.search("my dad collapsed and isn't breathing")
        assertEquals("cpr", r.best?.id)
        assertTrue(r.best!!.matched.isNotEmpty())
    }

    companion object {
        private lateinit var kb: KnowledgeBase

        @BeforeClass @JvmStatic
        fun open() {
            kb = KnowledgeBase.open(System.getProperty("kb.path"))
        }

        @AfterClass @JvmStatic
        fun close() = kb.close()
    }
}
