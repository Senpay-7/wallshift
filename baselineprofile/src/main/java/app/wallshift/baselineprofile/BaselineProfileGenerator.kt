package app.wallshift.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile for cold start and grid scrolling.
 *
 * Run on a connected device (add ~30+ wallpapers in the app first so the grid can scroll):
 *   ./gradlew :app:generateReleaseBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndScroll() = rule.collect(
        packageName = "app.wallshift",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        device.wait(Until.hasObject(By.res("grid")), 5_000)
        val grid = device.findObject(By.res("grid")) ?: return@collect
        grid.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            grid.fling(Direction.DOWN)
            device.waitForIdle()
            grid.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
