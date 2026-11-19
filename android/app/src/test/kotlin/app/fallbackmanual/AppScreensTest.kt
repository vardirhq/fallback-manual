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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class AppScreensTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) = rule.onRoot().captureRoboImage("${System.getProperty("screenshots.dir")}/$name.png")

    private fun waitFor(text: String) = rule.waitUntilAtLeastOneExists(hasText(text, substring = true), 30_000)

    @Test
    fun homeShowsEmergencyTilesAndCategories() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("DRAFT CONTENT · NOT YET REVIEWED").assertExists()
        rule.onNodeWithText("Severe bleeding").assertExists()
        shot("01-home")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Navigation and signaling"))
        shot("02-home-manual")
    }

    @Test
    fun openArticleFromEmergencyTile() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("Hypothermia").performClick()
        waitFor("WHEN TO USE")
        rule.onNodeWithText("DRAFT · NOT REVIEWED").assertExists()
        rule.onNodeWithText("Call emergency services first").assertExists()
        rule.onNodeWithText("FA-04 · FIRST AID").assertExists()
        // Sources are folded away until asked for.
        rule.onNodeWithText("Hypothermia, signs and first aid", substring = true).assertDoesNotExist()
        rule.onNodeWithText("SOURCES & REVIEW").performScrollTo().performClick()
        rule.onNodeWithText("Hypothermia, signs and first aid", substring = true).assertExists()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Hypothermia", substring = false))
        shot("03-article")
    }

    @Test
    fun stepByStepModeWalksThroughStepsWithBeatGuide() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("CPR (adults)").performClick()
        waitFor("STEP BY STEP")
        shot("03b-article-cpr")
        rule.onNodeWithText("STEP BY STEP").performClick()
        waitFor("FA-02 · STEP 1 OF 14")
        rule.onNodeWithText("Make sure it is safe for you to approach.").assertExists()
        rule.onNodeWithText("KEEP TO THE BEAT").assertDoesNotExist()
        rule.onNodeWithText("NEXT").performClick()
        waitFor("STEP 2 OF 14")
        shot("12-step-mode")
        repeat(7) { rule.onNodeWithText("NEXT").performClick() }
        waitFor("STEP 9 OF 14")
        rule.onNodeWithText("KEEP TO THE BEAT").assertExists() // CPR rhythm covers steps 9-13
        rule.onNodeWithText("110 a minute").assertExists()
        shot("13-step-mode-beat")
        rule.onNodeWithText("BACK").performClick()
        waitFor("STEP 8 OF 14")
        rule.onNodeWithText("KEEP TO THE BEAT").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close step-by-step mode").performClick()
        waitFor("STEP BY STEP")
    }

    @Test
    fun tabsSwitchBetweenHomeManualSavedAndShare() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("Manual").performClick()
        // Wait for the list itself: the top bar appears before the categories have loaded.
        waitFor("Fire and warmth")
        rule.onNodeWithText("OFFLINE MANUAL").assertExists()
        shot("14-manual-tab")
        rule.onNodeWithText("Saved").performClick()
        waitFor("Nothing saved yet")
        rule.onNodeWithText("Home").performClick()
        waitFor("EMERGENCY")
    }

    @Test
    fun searchFindsArticle() {
        waitFor("EMERGENCY")
        rule.onNode(hasSetTextAction()).performTextInput("my friend is shivering and confused")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor("BEST MATCH")
        rule.onNodeWithText("Hypothermia").assertExists()
        shot("04-search-match")
    }

    @Test
    fun searchSaysNoMatchInsteadOfGuessing() {
        waitFor("EMERGENCY")
        rule.onNode(hasSetTextAction()).performTextInput("snake bite")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor("No reviewed article matches")
        shot("05-search-no-match")
    }

    @Test
    fun saveArticleShowsOnHome() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("Burns and scalds").performClick()
        waitFor("WHEN TO USE")
        rule.onNodeWithContentDescription("Save article").performClick()
        rule.onNodeWithContentDescription("Remove from saved").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        waitFor("SAVED") // the Saved section on Home
    }

    private fun openCarbonMonoxideAndTapCprLink() {
        waitFor("EMERGENCY")
        rule.onNodeWithText("Carbon monoxide poisoning").performClick()
        waitFor("WHEN TO USE")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("start CPR", substring = true))
        rule.onNodeWithText("start CPR", substring = true).performFirstLinkClick()
        waitFor("Push hard and fast")
    }

    @Test
    fun linkOpensArticleInSheetAndClosingReturnsToArticle() {
        openCarbonMonoxideAndTapCprLink()
        rule.onNodeWithText("Open as full article").assertExists()
        captureScreenRoboImage("${System.getProperty("screenshots.dir")}/08-link-sheet.png")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitUntilDoesNotExist(hasText("Push hard and fast", substring = true), 5_000)
        // Still on the carbon monoxide article, at the same place.
        rule.onNodeWithText("start CPR", substring = true).assertExists()
    }

    @Test
    fun linkSheetCanOpenTheFullArticleAndBackReturns() {
        openCarbonMonoxideAndTapCprLink()
        rule.onNodeWithText("Open as full article").performScrollTo().performClick()
        rule.waitUntilDoesNotExist(hasText("Open as full article"), 5_000)
        rule.onNodeWithText("CPR (adults)").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        // Back on the carbon monoxide article, still scrolled to the CPR link rather than the top.
        waitFor("start CPR")
        rule.onNodeWithText("start CPR", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Carbon monoxide poisoning").assertIsNotDisplayed()
    }

    @Config(qualifiers = "+night")
    @Test
    fun darkMode() {
        waitFor("EMERGENCY")
        shot("06-home-dark")
    }
}
