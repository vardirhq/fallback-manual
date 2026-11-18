package app.fallbackmanual

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Shows a picture from the fixture knowledge base (tests/fixtures/knowledge), not the real manual. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
// Same Robolectric setup as the other screen tests, so all share one sandbox: the bundled
// SQLite native library can be loaded only once per test JVM.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi", application = PictureTest.FixtureApp::class)
class PictureTest {

    class FixtureApp : FallbackApp() {
        override val knowledge by lazy {
            KnowledgeStore(this) { File(System.getProperty("fixture.db")!!).inputStream() }
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val alt = "A red circle above a green line, used as a test picture"

    private fun openFixtureArticle() {
        rule.waitUntilAtLeastOneExists(hasText("First aid"), 30_000)
        rule.onNodeWithText("First aid").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Picture test"), 10_000)
        rule.onNodeWithText("Picture test").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription(alt), 10_000)
    }

    private fun shot(name: String) = captureScreenRoboImage("${System.getProperty("screenshots.dir")}/$name.png")

    @Test
    fun pictureShowsInsideItsStepWithItsDescription() {
        openFixtureArticle()
        rule.onNodeWithText(alt).assertExists() // caption under the picture
        shot("09-picture-in-step")
    }

    @Test
    fun tappingPictureOpensFullScreenAndCloses() {
        openFixtureArticle()
        rule.onNodeWithContentDescription(alt).performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("Close picture"), 5_000)
        rule.onNodeWithText("Fallback Manual test suite", substring = true).assertExists() // credit
        shot("10-picture-full-screen")
        rule.onNodeWithContentDescription("Close picture").performClick()
        rule.waitUntilDoesNotExist(hasContentDescription("Close picture"), 5_000)
    }

    @Config(qualifiers = "+night")
    @Test
    fun pictureStaysOnLightPanelInDarkMode() {
        openFixtureArticle()
        shot("11-picture-dark-mode")
    }
}
