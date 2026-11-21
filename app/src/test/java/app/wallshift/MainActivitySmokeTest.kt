package app.wallshift

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Launches the real Activity (real WallApp, DataStore, index) and opens the custom picker. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivitySmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsAndOpensCustomInterval() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Auto-change") > 0 }
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Turn on") > 0 } // live wallpaper is off here
        rule.onNodeWithText("Change now").assertExists()
        // Interval chips appear once auto-change is on.
        rule.onNodeWithText("Auto-change").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTextCount("Custom…") > 0 }
        rule.onNodeWithText("Custom…", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("Custom interval").assertExists()
        rule.onNodeWithText("Cancel").performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
