package app.wallshift.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CustomIntervalDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private var confirmed: Int? = null

    private fun show(initialSeconds: Int) = rule.setContent {
        AppTheme {
            CustomIntervalDialog(initialSeconds, onDismiss = {}, onConfirm = { confirmed = it })
        }
    }

    @Test
    fun initialValueAndUnitAreRestored() {
        show(10 * 60)
        rule.onNodeWithText("Set").performClick()
        assertEquals(600, confirmed)
    }

    @Test
    fun hoursAreDetected() {
        show(6 * 3600)
        rule.onNodeWithText("Set").performClick()
        assertEquals(6 * 3600, confirmed)
    }

    @Test
    fun switchingUnitKeepsNumber() {
        show(10 * 60)
        rule.onNodeWithText("Seconds").performClick()
        rule.onNodeWithText("Set").performClick()
        assertEquals(10, confirmed)
    }

    @Test
    fun tappingANumberSelectsIt() {
        show(10)
        rule.onNodeWithText("12").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Set").performClick()
        assertEquals(12, confirmed)
    }

    @Test
    fun flingSnapsToAValidNumber() {
        show(30)
        rule.onNodeWithTag("wheel").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("Set").performClick()
        val v = confirmed!!
        assertTrue("got $v", v in 31..60)
    }
}
