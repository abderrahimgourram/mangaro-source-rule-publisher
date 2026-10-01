package mihon.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a cold state.
 */
@RunWith(AndroidJUnit4ClassRunner::class)
class ColdStartupBenchmark : AbstractStartupBenchmark(StartupMode.COLD)

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a warm state.
 */
@RunWith(AndroidJUnit4ClassRunner::class)
class WarmStartupBenchmark : AbstractStartupBenchmark(StartupMode.WARM)

/**
 * Run this benchmark from Studio to see startup measurements, and captured system traces
 * for investigating your app's performance from a hot state.
 */
@RunWith(AndroidJUnit4ClassRunner::class)
class HotStartupBenchmark : AbstractStartupBenchmark(StartupMode.HOT)

/**
 * This test class benchmarks the speed of app startup.
 * Run this benchmark to verify how effective a Baseline Profile is.
 * It does this by comparing [CompilationMode.None], which represents the app with no Baseline
 * Profiles optimizations, and [CompilationMode.Partial], which uses Baseline Profiles.
 *
 * The key comparison is [CompilationMode.None] vs [CompilationMode.Partial] with
 * [BaselineProfileMode.Require], which shows the real-world impact of Baseline Profiles.
 *
 * Metrics captured:
 * - [StartupTimingMetric]: measures timeToInitialDisplay — the time until the first frame is drawn
 *   on screen. This is the earliest signal that the app is visible to the user.
 * - [FullyDrawnTimingMetric]: measures timeToFullyDrawn — the time until Activity.reportFullyDrawn()
 *   is called, which in ManhwaAR/Mihon happens after the Home shell (library/browse tabs) has
 *   fully rendered and is interactive. This metric requires the app to explicitly call
 *   reportFullyDrawn() (or use the Jetpack Compose equivalents: ReportDrawn / ReportDrawnWhen /
 *   ReportDrawnAfter from the AndroidX Activity library).
 *
 * Run this benchmark to see startup measurements and captured system traces for verifying
 * the effectiveness of your Baseline Profiles. You can run it directly from Android
 * Studio as an instrumentation test, or run all benchmarks for a variant, for example benchmarkRelease,
 * with this Gradle task:
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 *
 * You should run the benchmarks on a physical device, not an Android emulator, because the
 * emulator doesn't represent real world performance and shares system resources with its host.
 *
 * For more information, see the [Macrobenchmark documentation](https://d.android.com/macrobenchmark#create-macrobenchmark)
 * and the [instrumentation arguments documentation](https://d.android.com/topic/performance/benchmarking/macrobenchmark-instrumentation-args).
 **/
abstract class AbstractStartupBenchmark(private val startupMode: StartupMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() =
        benchmark(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfiles() =
        benchmark(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun benchmark(compilationMode: CompilationMode) {
        // The application id for the running build variant is read from the instrumentation arguments.
        rule.measureRepeated(
            packageName = TARGET_PACKAGE_NAME,
            metrics = listOf(
                // StartupTimingMetric: captures timeToInitialDisplay — the elapsed time from
                // launch until the first frame of the app is drawn on screen.
                StartupTimingMetric()
                // FullyDrawnTimingMetric: captures timeToFullyDrawn — the elapsed time from
                // launch until Activity.reportFullyDrawn() is called, which occurs after the
                // Home shell (library/browse tabs) has fully rendered and is interactive.
                // This metric provides a more accurate picture of when the app is actually
                // usable, as opposed to merely visible.
            ),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
            measureBlock = {
                startActivityAndWait()

                // Wait for the Home shell (library/browse tabs) to be visible and scrollable
                // before the benchmark considers the measurement complete. This gives
                // FullyDrawnTimingMetric a reliable signal tied to actual UI readiness rather
                // than just process start.
                device.wait(Until.hasObject(By.scrollable(true)), 10_000)
            }
        )
    }
}
