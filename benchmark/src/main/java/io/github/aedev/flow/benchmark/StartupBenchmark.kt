package io.github.aedev.flow.benchmark

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * General startup macrobenchmarks measuring Cold, Warm, and Hot app startup performance.
 *
 * Measures:
 * - Time to Initial Display (TTID): Time until the first frame is rendered.
 * - Time to Full Display (TTFD): Time until the Home feed content settles and reports drawn.
 *
 * Run with:
 *   ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest --no-configuration-cache
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = measureStartup(startupMode = StartupMode.COLD)

    @Test
    fun warmStartup() = measureStartup(StartupMode.WARM)

    @Test
    fun hotStartup() = measureStartup(startupMode = StartupMode.HOT)

    private fun measureStartup(
        startupMode: StartupMode,
        compilationMode: CompilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    ) {
        val targetPackage =
            InstrumentationRegistry.getArguments().getString("targetAppId")
                ?: error("targetAppId instrumentation argument was not supplied")

        rule.measureRepeated(
            packageName = targetPackage,
            // The engine loads beside the first frame since #1301; its own time shows here.
            metrics = listOf(StartupTimingMetric(), TraceSectionMetric("neuro.init", TraceSectionMetric.Mode.First)),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait { intent ->
                intent.putExtra("io.github.aedev.flow.extra.BENCHMARK_BYPASS_ONBOARDING", true)
            }

            device.wait(Until.hasObject(By.res("home_feed")), 5000)
        }
    }
}
