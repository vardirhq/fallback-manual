package app.fallbackmanual

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Saved articles, kept on the device only. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    var ids: Set<String> by mutableStateOf(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
        private set

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "ids"
    }
}
