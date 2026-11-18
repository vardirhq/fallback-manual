package app.fallbackmanual

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// Same Robolectric setup as the other screen tests, so all share one sandbox: the bundled
// SQLite native library can be loaded only once per test JVM.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class InstallSourceTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun classifiesKnownInstallers() {
        assertEquals(InstallChannel.PlayStore, InstallSources.classify("com.android.vending"))
        assertEquals(InstallChannel.FDroid, InstallSources.classify("org.fdroid.fdroid"))
        assertEquals(InstallChannel.FDroid, InstallSources.classify("com.looker.droidify"))
        assertEquals(InstallChannel.ApkFile, InstallSources.classify("com.google.android.packageinstaller"))
        assertEquals(InstallChannel.ApkFile, InstallSources.classify("com.google.android.apps.nbu.files"))
        assertEquals(InstallChannel.Unknown, InstallSources.classify(null))
        assertEquals(InstallChannel.Unknown, InstallSources.classify(""))
    }

    private fun aboutShows(installing: String?, initiating: String?, expected: String) {
        rule.waitUntilAtLeastOneExists(hasText("Emergency"), 30_000)
        // Set only after the app has loaded: changing package info earlier interferes with how
        // Robolectric serves the app's assets (the knowledge database).
        val app = rule.activity.application
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, initiating, installing)
        rule.onNodeWithContentDescription("About").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Installation"), 10_000)
        rule.onNodeWithText(expected, substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("never checks for updates", substring = true).assertExists()
    }

    @Test
    fun aboutSaysPlay() = aboutShows("com.android.vending", "com.android.vending", "Installed from Google Play")

    @Test
    fun aboutSaysFDroid() = aboutShows("org.fdroid.fdroid", "org.fdroid.fdroid", "Installed from F-Droid")

    @Test
    fun aboutSaysApkFile() =
        aboutShows("com.google.android.packageinstaller", "com.google.android.apps.nbu.files", "Installed from an app file")
}
