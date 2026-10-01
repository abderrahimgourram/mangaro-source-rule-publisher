package mihon.baselineprofile

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.MacrobenchmarkScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LAUNCH_TIMEOUT_MS = 10_000L

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE_NAME,
        includeInStartupProfile = false,
        maxIterations = 2,
    ) {
        pressHome()
        startActivityAndWait()
        profileSetup()
    }
}

private fun MacrobenchmarkScope.profileSetup() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // Wait for the Home shell to appear — detected by a scrollable container
    device.wait(Until.findObject(By.scrollable(true)), LAUNCH_TIMEOUT_MS)
    Thread.sleep(1000)

    // Scroll down on Home to exercise the Home feed rendering path
    device.scrollDown()
    Thread.sleep(500)
    device.scrollDown()
    Thread.sleep(500)

    // Navigate to Library tab — Arabic content description
    device.waitAndClick(By.desc("المكتبة"))
    Thread.sleep(500)

    // Navigate to Browse (Explore) tab — Arabic content description
    device.waitAndClick(By.desc("الاستكشاف"))
    Thread.sleep(500)

    // Navigate to More tab — Arabic content description
    device.waitAndClick(By.desc("المزيد"))
    Thread.sleep(500)

    // Return to Home tab — Arabic content description
    device.waitAndClick(By.desc("الرئيسية"))
    Thread.sleep(500)
}

/**
 * Waits up to [LAUNCH_TIMEOUT_MS] for a matching element and clicks it.
 * No-ops silently if the element never appears (keeps the profile run deterministic).
 */
private fun UiDevice.waitAndClick(by: BySelector) {
    val obj = wait(Until.findObject(by), LAUNCH_TIMEOUT_MS) ?: throw AssertionError("Required automation element not found: $by")
    obj.click()
}

/**
 * Performs a short downward swipe in the centre of the screen to scroll content.
 */
private fun UiDevice.scrollDown() {
    val startX = displayWidth / 2
    val startY = (displayHeight * 0.7).toInt()
    val endY = (displayHeight * 0.3).toInt()
    swipe(startX, startY, startX, endY, 20)
}
