package app.fallbackmanual

import android.app.Application
import android.content.Context
import app.fallbackmanual.core.KnowledgeBase
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

open class FallbackApp : Application() {
    open val knowledge by lazy { KnowledgeStore(this) }
    val bookmarks by lazy { Bookmarks(this) }
    open val sharing by lazy { AppSharing(this) }
}

/**
 * Owns the single [KnowledgeBase] connection. The database ships in the APK's assets; SQLite
 * can't open it there, so it is copied to private storage once per app install or update.
 * All access runs on one background thread, because a connection is not thread-safe.
 *
 * There is one database per language (knowledge.db is English, knowledge-nb.db Norwegian
 * Bokmål, ...). Each holds every article: translated where a current translation exists,
 * English otherwise. The one used follows the app's language, which can change while it runs.
 */
class KnowledgeStore(
    private val context: Context,
    /** Where a database comes from, by asset name. Tests replace this with a fixture database. */
    private val source: ((String) -> java.io.InputStream)? = null,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "knowledge-db") }
        .asCoroutineDispatcher()
    private var kb: KnowledgeBase? = null
    private var kbAsset: String? = null

    suspend fun <T> use(block: (KnowledgeBase) -> T): T = withContext(dispatcher) { block(open()) }

    /** The database for the app's current language, or English if there is none for it. */
    private fun asset(): String {
        val lang = context.resources.configuration.locales[0].language
        val wanted = LANGUAGE_ASSETS[lang] ?: return ASSET
        if (source != null) return wanted
        return if (runCatching { context.assets.open(wanted).close() }.isSuccess) wanted else ASSET
    }

    private fun open(): KnowledgeBase {
        val asset = asset()
        if (asset == kbAsset) kb?.let { return it }
        kb?.close()
        kb = null
        val file = File(context.noBackupFilesDir, asset)
        val prefs = context.getSharedPreferences("knowledge", Context.MODE_PRIVATE)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!file.exists() || prefs.getLong("copied_for:$asset", -1) != installed) {
            val tmp = File(file.path + ".tmp")
            (source?.invoke(asset) ?: context.assets.open(asset)).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(file)) { "could not install knowledge database" }
            prefs.edit().putLong("copied_for:$asset", installed).apply()
        }
        return KnowledgeBase.open(file.path).also { kb = it; kbAsset = asset }
    }

    private companion object {
        const val ASSET = "knowledge.db"
        /** Phone language -> database. Nynorsk and plain "no" readers get Bokmål rather than English. */
        val LANGUAGE_ASSETS = mapOf("nb" to "knowledge-nb.db", "no" to "knowledge-nb.db", "nn" to "knowledge-nb.db")
    }
}
