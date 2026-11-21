package io.github.aedev.flow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.benchmark.NavigationJourneys.openAndCloseSettings
import io.github.aedev.flow.benchmark.NavigationJourneys.openHome
import io.github.aedev.flow.benchmark.NavigationJourneys.scrollHome
import io.github.aedev.flow.benchmark.NavigationJourneys.switchTabs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for moving around the app: tab switches, opening and leaving a page, and scrolling
 * a feed whose header and bar move with it.
 *
 * Run with:
 *   ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest --no-configuration-cache \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.aedev.flow.benchmark.NavigationBenchmark
 */
@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun tabSwitches() = measure { switchTabs() }

    @Test
    fun pageOpenAndBack() = measure { openAndCloseSettings() }

    @Test
    fun feedScroll() = measure { scrollHome() }

    private fun measure(journey: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName =
                InstrumentationRegistry.getArguments().getString("targetAppId")
                    ?: error("targetAppId instrumentation argument was not supplied"),
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
                openHome()
            },
            measureBlock = journey,
        )

    private companion object {
        const val ITERATIONS = 5
    }
}
