package io.github.aedev.flow.benchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Feeds reached from a warm Home, each flung down three times and up once by [flingFeed]. */
internal object FeedJourneys {
    private const val SETTLE_MS = 1_500L
    private const val FIND_TIMEOUT_MS = 10_000L
    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val SEARCH_QUERY = "lofi hip hop"
    private const val CHANNEL_URL = "https://www.youtube.com/@YouTube/videos"
    private const val WATCH_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    private val FLINGS = listOf(Direction.DOWN, Direction.DOWN, Direction.DOWN, Direction.UP)

    fun MacrobenchmarkScope.flingFeed() {
        FLINGS.forEach { direction ->
            val feed = largestScrollable() ?: return
            runCatching {
                feed.setGestureMargin(device.displayWidth / 5)
                feed.fling(direction)
            }
            device.waitForIdle(SETTLE_MS)
        }
    }

    fun MacrobenchmarkScope.openSubscriptions() = openTab("Subs")

    fun MacrobenchmarkScope.openMusicHome() = openTab("Music")

    fun MacrobenchmarkScope.openSearchResults() {
        device.wait(Until.findObject(By.desc("Search")), FIND_TIMEOUT_MS)?.click() ?: return
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), FIND_TIMEOUT_MS) ?: return
        field.text = SEARCH_QUERY
        device.pressEnter()
        awaitFeed()
    }

    fun MacrobenchmarkScope.openChannelVideos() {
        openLink(CHANNEL_URL)
        device.wait(Until.findObject(By.text("Videos")), FIND_TIMEOUT_MS)?.click()
        awaitFeed()
    }

    fun MacrobenchmarkScope.openWatchPage() {
        openLink(WATCH_URL)
        awaitFeed()
    }

    private fun MacrobenchmarkScope.openTab(label: String) {
        device.wait(Until.findObject(By.text(label)), FIND_TIMEOUT_MS)?.click() ?: return
        awaitFeed()
    }

    private fun MacrobenchmarkScope.openLink(url: String) {
        startActivityAndWait(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(packageName)
                .putExtra(NavigationJourneys.BYPASS_ONBOARDING, true),
        )
    }

    private fun MacrobenchmarkScope.awaitFeed() {
        device.wait(Until.hasObject(By.scrollable(true)), LOAD_TIMEOUT_MS)
        device.waitForIdle(SETTLE_MS)
    }

    // The watch page and channel header hold small horizontal scrollers; the feed is the tallest one.
    private fun MacrobenchmarkScope.largestScrollable(): UiObject2? =
        device
            .findObjects(By.scrollable(true))
            .maxByOrNull { it.visibleBounds.height() }
}
