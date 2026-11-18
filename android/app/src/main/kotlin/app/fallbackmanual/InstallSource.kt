package app.fallbackmanual

import android.content.Context
import android.os.Build

/** Where this copy of the app came from, as reported by Android. Works offline. */
enum class InstallChannel { PlayStore, FDroid, ApkFile, Unknown }

data class InstallSource(val channel: InstallChannel, val installer: String?)

object InstallSources {
    private val PLAY = setOf("com.android.vending")
    private val FDROID = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "org.fdroid.fdroid.privileged",
        "com.looker.droidify", // Droid-ify
        "com.machiav3lli.fdroid", // Neo Store
    )

    /**
     * Classifies an installer package name. This is a hint, not proof: some stores (e.g. Aurora)
     * can report themselves as Google Play, and backups or phone transfers can change the value.
     * Anything that isn't a known store means the app was installed from an APK file, whether by
     * a file manager, a browser, Quick Share or Android's own package installer.
     */
    fun classify(installer: String?): InstallChannel = when {
        installer.isNullOrBlank() -> InstallChannel.Unknown
        installer in PLAY -> InstallChannel.PlayStore
        installer in FDROID -> InstallChannel.FDroid
        else -> InstallChannel.ApkFile
    }

    fun detect(context: Context): InstallSource {
        val pm = context.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(context.packageName)
                // A store installs apps itself; for an APK file the app that opened it is more telling.
                val installing = info.installingPackageName
                if (classify(installing) == InstallChannel.ApkFile) info.initiatingPackageName ?: installing else installing
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return InstallSource(classify(installer), installer)
    }
}
