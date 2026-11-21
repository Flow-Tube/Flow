package io.github.aedev.flow.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** Journeys through the app shell, shared by the navigation benchmark and the baseline profile. */
internal object NavigationJourneys {
    const val BYPASS_ONBOARDING = "io.github.aedev.flow.extra.BENCHMARK_BYPASS_ONBOARDING"
    private const val SETTLE_MS = 1_500L
    private const val FIND_TIMEOUT_MS = 10_000L
    private val TABS = listOf("Subs", "Library", "Home")

    fun MacrobenchmarkScope.openHome() {
        startActivityAndWait { it.putExtra(BYPASS_ONBOARDING, true) }
        device.wait(Until.hasObject(By.res("home_video_card")), FIND_TIMEOUT_MS)
    }

    /** Home to Subscriptions to Library and back to Home through the bar: three fade-throughs. */
    fun MacrobenchmarkScope.switchTabs() {
        TABS.forEach { label ->
            device.wait(Until.findObject(By.text(label)), FIND_TIMEOUT_MS)?.click() ?: return
            device.waitForIdle(SETTLE_MS)
        }
    }

    /** Opens Settings from the top bar and leaves with Back: one slide in, one slide out. */
    fun MacrobenchmarkScope.openAndCloseSettings() {
        device.wait(Until.findObject(By.desc("Settings")), FIND_TIMEOUT_MS)?.click() ?: return
        device.waitForIdle(SETTLE_MS)
        device.pressBack()
        device.waitForIdle(SETTLE_MS)
    }

    /** Scrolls Home down and back, moving the scroll-away header and the bottom bar. */
    fun MacrobenchmarkScope.scrollHome() {
        listOf(Direction.DOWN, Direction.DOWN, Direction.UP, Direction.UP).forEach { direction ->
            val feed = device.wait(Until.findObject(By.scrollable(true)), FIND_TIMEOUT_MS) ?: return
            runCatching {
                feed.setGestureMargin(device.displayWidth / 5)
                feed.scroll(direction, 0.6f)
            }
            device.waitForIdle(SETTLE_MS)
        }
    }
}
