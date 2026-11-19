package app.fallbackmanual

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translation (values-<language>/strings.xml) must have exactly the same strings as the English
 * values/strings.xml, with the same placeholders (%1$s, %2$d ...). A missing string would silently
 * show English; a wrong placeholder crashes the screen that uses it.
 */
class StringsTest {
    private val res = File("src/main/res")

    /** name -> placeholders, for strings and every quantity of plurals. */
    private fun read(file: File): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = sortedMapOf<String, List<String>>()
        val placeholder = Regex("""%\d+\$[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
        fun add(name: String, text: String) { out[name] = placeholder.findAll(text).map { it.value }.sorted().toList() }
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            if (e.getAttribute("translatable") != "false") add(e.getAttribute("name"), e.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as Element
            val items = e.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                // Languages differ in which quantities they need, so compare only "other".
                if (item.getAttribute("quantity") == "other") add(e.getAttribute("name"), item.textContent)
            }
        }
        return out
    }

    @Test
    fun translationsMatchEnglish() {
        val english = read(File(res, "values/strings.xml"))
        val translations = res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        assertTrue("expected at least one translation", translations.isNotEmpty())
        for (dir in translations) {
            val t = read(File(dir, "strings.xml"))
            assertEquals("${dir.name}: names differ from values/strings.xml", english.keys, t.keys)
            for ((name, ph) in english) assertEquals("${dir.name}/$name: placeholders", ph, t[name])
        }
    }

    @Test
    fun everyTranslationIsInLocaleConfig() {
        val config = File(res, "xml/locales_config.xml").readText()
        res.listFiles { f -> f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!.forEach { dir ->
            val lang = dir.name.removePrefix("values-")
            assertTrue("add <locale android:name=\"$lang\" /> to res/xml/locales_config.xml", "\"$lang\"" in config)
        }
    }
}
