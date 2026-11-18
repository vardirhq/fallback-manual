package app.fallbackmanual

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real app (real database, real search) on the desktop and saves screenshots to
 * docs/screenshots. This is not a substitute for testing on a phone: it cannot check the
 * Android build of the bundled SQLite library.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class AppScreensTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = rule.onRoot().captureRoboImage("${System.getProperty("screenshots.dir")}/$name.png")

    private fun waitFor(text: String) = rule.waitUntilAtLeastOneExists(hasText(text, substring = true), 10_000)

    @Test
    fun homeShowsEmergencyTilesAndCategories() {
        waitFor("Emergency")
        rule.onNodeWithText("Development build").assertExists()
        rule.onNodeWithText("Severe bleeding").assertExists()
        shot("01-home")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Navigation and signaling"))
        shot("02-home-manual")
    }

    @Test
    fun openArticleFromEmergencyTile() {
        waitFor("Emergency")
        rule.onNodeWithText("Hypothermia").performClick()
        waitFor("When to use")
        rule.onNodeWithText("Unreviewed draft").assertExists()
        rule.onNodeWithText("If you can, call emergency services now").assertExists()
        shot("03-article")
    }

    @Test
    fun searchFindsArticle() {
        waitFor("Emergency")
        rule.onNode(hasSetTextAction()).performTextInput("my friend is shivering and confused")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor("Best match")
        rule.onNodeWithText("Hypothermia").assertExists()
        shot("04-search-match")
    }

    @Test
    fun searchSaysNoMatchInsteadOfGuessing() {
        waitFor("Emergency")
        rule.onNode(hasSetTextAction()).performTextInput("snake bite")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor("No reviewed article matches")
        shot("05-search-no-match")
    }

    @Test
    fun saveArticleShowsOnHome() {
        waitFor("Emergency")
        rule.onNodeWithText("Burns and scalds").performClick()
        waitFor("When to use")
        rule.onNodeWithContentDescription("Save article").performClick()
        rule.onNodeWithContentDescription("Remove from saved").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("Saved")
    }

    @Config(qualifiers = "+night")
    @Test
    fun darkMode() {
        waitFor("Emergency")
        shot("06-home-dark")
    }
}
