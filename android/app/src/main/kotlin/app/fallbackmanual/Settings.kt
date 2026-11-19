package app.fallbackmanual

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class ThemeMode { System, Light, Dark }

/**
 * The reader's own choices, stored on the phone: light or dark theme, and the app's language.
 *
 * Language: on Android 13 and later, this is Android's own per-app language (the same setting as
 * Settings > Apps > Fallback Manual > Language), which Android stores and applies itself. Older
 * versions have no such setting, so the choice is stored here and applied in
 * [MainActivity.attachBaseContext].
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var themeState by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(THEME, null) ?: "") }.getOrDefault(ThemeMode.System),
    )

    /** Light, dark, or following the phone. Compose screens that read it update when it changes. */
    var theme: ThemeMode
        get() = themeState
        set(mode) {
            themeState = mode
            prefs.edit().putString(THEME, mode.name).apply()
        }

    /** The chosen language ("en", "nb"), or null to follow the phone. */
    fun language(): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.language
        } else {
            prefs.getString(LANGUAGE, null)
        }

    /** Changes the app's language (null: follow the phone). The screen is rebuilt in the new language. */
    fun setLanguage(activity: Activity, tag: String?) {
        if (tag == language()) return
        if (Build.VERSION.SDK_INT >= 33) {
            // Android stores it, and recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs.edit().putString(LANGUAGE, tag).apply()
            activity.recreate()
        }
    }

    /** [base] with the chosen language applied, for Android versions without a per-app language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs.getString(LANGUAGE, null) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    companion object {
        private const val THEME = "theme"
        private const val LANGUAGE = "language"

        /** The languages the app is translated into, each named in its own language. */
        val LANGUAGES = listOf("en" to "English", "nb" to "Norsk bokmål")
    }
}
