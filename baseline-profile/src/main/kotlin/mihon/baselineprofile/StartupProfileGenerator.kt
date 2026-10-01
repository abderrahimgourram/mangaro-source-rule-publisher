package mihon.baselineprofile

import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LAUNCH_TIMEOUT_MS = 10_000L

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = TARGET_PACKAGE_NAME,
            includeInStartupProfile = true,
            maxIterations = 2,
        ) {
            pressHome()
            startActivityAndWait()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wait(Until.hasObject(By.scrollable(true)), LAUNCH_TIMEOUT_MS)
        }
    }
}
