package app.fallbackmanual

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Passing the app to a nearby phone without internet.
 *
 * The app sends its own installed APK through the system share sheet, which offers Quick
 * Share / Nearby Share, Bluetooth and similar offline transports. It needs no permissions:
 * every app can read its own APK, and FileProvider grants the receiving app temporary read
 * access to the copy.
 */
class AppSharing(
    private val context: Context,
    /** The installed APK and any split APKs. Replaceable so tests can supply a fake APK. */
    private val apkPath: () -> String? = { context.applicationInfo.sourceDir },
    private val splitPaths: () -> Array<String>? = { context.applicationInfo.splitSourceDirs },
) {

    sealed interface Availability {
        data class Ready(val sizeBytes: Long) : Availability

        /** Installed from a store as split APKs: the base APK alone would not install. */
        data object SplitInstall : Availability
        data object Missing : Availability
    }

    private val versionName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"

    fun availability(): Availability {
        if (!splitPaths().isNullOrEmpty()) return Availability.SplitInstall
        val apk = apkPath()?.let(::File)
        return if (apk != null && apk.isFile) Availability.Ready(apk.length()) else Availability.Missing
    }

    /** Copies the installed APK to the share cache, as a plain APK or wrapped in a ZIP. */
    suspend fun prepare(asZip: Boolean): File = withContext(Dispatchers.IO) {
        val source = File(apkPath() ?: error("app file not found"))
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        val base = "FallbackManual-$versionName"
        dir.listFiles()?.filter { !it.name.startsWith(base) }?.forEach { it.delete() } // old versions
        if (asZip) {
            val out = File(dir, "$base.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("$base.apk"))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            out
        } else {
            File(dir, "$base.apk").also { out ->
                if (out.length() != source.length()) source.copyTo(out, overwrite = true)
            }
        }
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "zip") "application/zip" else APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Fallback Manual (offline emergency manual)")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send Fallback Manual")
    }

    /** Writes the APK to a location the user picked (SD card, USB stick, Downloads). */
    suspend fun saveTo(target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target) ?: error("could not open the chosen location")
        out.use { o -> File(apkPath() ?: error("app file not found")).inputStream().use { it.copyTo(o) } }
    }

    fun suggestedFileName(): String = "FallbackManual-$versionName.apk"

    /**
     * SHA-256 of the certificate the app is signed with, as "AB12 CD34 ...". Android refuses to
     * install a modified APK unless it is re-signed with a different key, so two phones showing
     * the same fingerprint are running the same publisher's build.
     */
    fun signingFingerprint(): String? = runCatching {
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = certs?.firstOrNull() ?: return null
        formatFingerprint(MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()))
    }.getOrNull()

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val SHARE_DIR = "share"

        fun authority(context: Context) = "${context.packageName}.share"

        fun formatFingerprint(bytes: ByteArray): String =
            bytes.joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    }
}
