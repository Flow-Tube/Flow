package io.github.aedev.flow.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.benchmark.FeedJourneys.flingFeed
import io.github.aedev.flow.benchmark.FeedJourneys.openChannelVideos
import io.github.aedev.flow.benchmark.FeedJourneys.openMusicHome
import io.github.aedev.flow.benchmark.FeedJourneys.openSearchResults
import io.github.aedev.flow.benchmark.FeedJourneys.openSubscriptions
import io.github.aedev.flow.benchmark.FeedJourneys.openWatchPage
import io.github.aedev.flow.benchmark.NavigationJourneys.openHome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while flinging each card feed: down three times and up once, after the feed has
 * loaded. Pair a run with the debug image byte log to see what each fling fetched.
 *
 * Run with:
 *   ./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest --no-configuration-cache \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.aedev.flow.benchmark.FeedScrollBenchmark
 */
@RunWith(AndroidJUnit4::class)
class FeedScrollBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun home() = measureFling {}

    @Test
    fun subscriptions() = measureFling { openSubscriptions() }

    @Test
    fun searchResults() = measureFling { openSearchResults() }

    @Test
    fun channelVideos() = measureFling { openChannelVideos() }

    @Test
    fun watchPageRelated() = measureFling { openWatchPage() }

    @Test
    fun musicHome() = measureFling { openMusicHome() }

    private fun measureFling(openFeed: MacrobenchmarkScope.() -> Unit) =
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
                openFeed()
            },
            measureBlock = { flingFeed() },
        )

    private companion object {
        const val ITERATIONS = 5
    }
}
