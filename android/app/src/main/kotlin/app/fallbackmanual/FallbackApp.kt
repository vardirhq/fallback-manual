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
 */
class KnowledgeStore(
    private val context: Context,
    /** Where the database comes from. Tests replace this with a fixture database. */
    private val source: () -> java.io.InputStream = { context.assets.open(ASSET) },
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "knowledge-db") }
        .asCoroutineDispatcher()
    private var kb: KnowledgeBase? = null

    suspend fun <T> use(block: (KnowledgeBase) -> T): T = withContext(dispatcher) { block(open()) }

    private fun open(): KnowledgeBase {
        kb?.let { return it }
        val file = File(context.noBackupFilesDir, "knowledge.db")
        val prefs = context.getSharedPreferences("knowledge", Context.MODE_PRIVATE)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        if (!file.exists() || prefs.getLong("copied_for", -1) != installed) {
            val tmp = File(file.path + ".tmp")
            source().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(file)) { "could not install knowledge database" }
            prefs.edit().putLong("copied_for", installed).apply()
        }
        return KnowledgeBase.open(file.path).also { kb = it }
    }

    private companion object {
        const val ASSET = "knowledge.db"
    }
}
