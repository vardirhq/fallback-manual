package app.fallbackmanual

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipInputStream

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi", application = ShareTest.TestApp::class)
class ShareTest {

    /** The real app, except that sharing reads a fake APK whose bytes the test knows. */
    class TestApp : FallbackApp() {
        val fakeApk = ByteArray(300_000) { (it * 31 % 251).toByte() }
        var splits: Array<String>? = null
        override val sharing by lazy {
            val f = File(filesDir, "installed.apk").apply { writeBytes(fakeApk) }
            AppSharing(this, apkPath = { f.path }, splitPaths = { splits })
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * FileProvider caches its configured folders in a static map. Robolectric gives every test a
     * new data folder within one JVM, so the cache would point at the previous test's folder.
     * (On a phone the folder never changes.)
     */
    @org.junit.Before
    fun clearFileProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        synchronized(f.get(null)!!) { (f.get(null) as MutableMap<*, *>).clear() }
    }

    private val app get() = rule.activity.application as TestApp
    private val fakeApk get() = app.fakeApk

    private fun openShareScreen() {
        rule.waitUntilAtLeastOneExists(hasText("Emergency"), 10_000)
        rule.onNodeWithContentDescription("Share this app").performClick()
        rule.waitUntilAtLeastOneExists(hasText("On the other phone"), 5_000)
    }

    /** Clicks a send button and returns the intent handed to the share sheet, plus the file's bytes. */
    private fun sendAndCapture(button: String): Pair<Intent, ByteArray> {
        rule.onNodeWithText(button, substring = true).performClick()
        val shadow = shadowOf(rule.activity)
        rule.waitUntil(5_000) {
            shadow.peekNextStartedActivity() != null ||
                rule.onAllNodes(hasText("Could not", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Could not", substring = true).assertDoesNotExist()
        val chooser = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${rule.activity.packageName}.share", uri.authority)
        val bytes = rule.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return send to bytes
    }

    @Test
    fun sendsAnExactCopyOfTheApk() {
        openShareScreen()
        val (send, bytes) = sendAndCapture("Send with")
        assertEquals(AppSharing.APK_MIME, send.type)
        assertArrayEquals(fakeApk, bytes)
    }

    @Test
    fun shareScreenScreenshot() {
        openShareScreen()
        rule.onRoot().captureRoboImage("${System.getProperty("screenshots.dir")}/07-share.png")
    }

    @Test
    fun zipContainsTheApk() {
        openShareScreen()
        val (send, bytes) = sendAndCapture("Send as ZIP")
        assertEquals("application/zip", send.type)
        ZipInputStream(bytes.inputStream()).use { zip ->
            val entry = zip.nextEntry
            assertNotNull(entry)
            assertTrue(entry!!.name.endsWith(".apk"))
            assertArrayEquals(fakeApk, zip.readBytes())
            assertEquals(null, zip.nextEntry)
        }
    }

    @Test
    fun storeSplitInstallExplainsWhyItCannotShare() {
        app.splits = arrayOf("/data/app/split_config.arm64_v8a.apk")
        openShareScreen()
        rule.onNodeWithText("This copy can't be shared").assertExists()
        rule.onNodeWithText("Send with", substring = true).assertDoesNotExist()
    }

    @Test
    fun fingerprintFormat() {
        assertEquals("0001 0AFF", AppSharing.formatFingerprint(byteArrayOf(0x00, 0x01, 0x0A, 0xFF.toByte())))
    }
}
