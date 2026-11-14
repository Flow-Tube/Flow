package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.WatchProgress
import org.junit.Test

/** #979: every watched video filter reads [WatchedThreshold.isWatched] rather than restating it in SQL. */
class WatchedThresholdTest {
    private fun progress(
        id: String,
        positionMs: Long,
        durationMs: Long,
    ) = WatchProgress(videoId = id, position = positionMs, duration = durationMs, timestamp = 0L)

    private fun List<WatchProgress>.watchedIds(threshold: WatchedThreshold) =
        filter { threshold.isWatched(it.position, it.duration) }.map { it.videoId }

    private val matrix =
        listOf(
            progress("short-start", 1_500L, 30_000L),
            progress("short-half", 15_000L, 30_000L),
            progress("short-92", 27_600L, 30_000L),
            progress("short-done", 30_000L, 30_000L),
            progress("long-last-minute", 570_000L, 600_000L),
            progress("long-61s-left", 539_000L, 600_000L),
            progress("long-97", 5_820_000L, 6_000_000L),
            progress("feature-99", 11_900_000L, 12_000_000L),
            progress("unknown-length", 5_000L, 0L),
            progress("unplayed", 0L, 30_000L),
        )

    @Test
    fun `almost finished is the last minute of a long video and 90 percent of a short one`() {
        assertThat(matrix.watchedIds(WatchedThreshold.ALMOST_FINISHED))
            .containsExactly("short-92", "short-done", "long-last-minute")
    }

    @Test
    fun `a percent threshold ignores the time left`() {
        assertThat(matrix.watchedIds(WatchedThreshold.PERCENT_99)).containsExactly("short-done", "feature-99")
        assertThat(matrix.watchedIds(WatchedThreshold.PERCENT_90))
            .containsExactly("short-92", "short-done", "long-last-minute", "long-97", "feature-99")
    }

    @Test
    fun `a Short that only just started is never watched`() {
        WatchedThreshold.entries.forEach { threshold ->
            assertThat(threshold.isWatched(1_500L, 30_000L)).isFalse()
        }
    }

    @Test
    fun `an imported row has neither a position nor a duration`() {
        assertThat(isImportedWatch(0L, 0L)).isTrue()
        assertThat(isImportedWatch(0L, 600_000L)).isFalse()
        assertThat(isImportedWatch(5_000L, 0L)).isFalse()
        assertThat(isImportedWatch(0L, 1L)).isFalse()
    }

    @Test
    fun `imported rows count as watched only when the viewer asks`() {
        WatchedThreshold.entries.forEach { threshold ->
            assertThat(threshold.isWatched(0L, 0L, countImported = false)).isFalse()
            assertThat(threshold.isWatched(0L, 0L, countImported = true)).isTrue()
            assertThat(threshold.isWatched(0L, 600_000L, countImported = true)).isFalse()
        }
        assertThat(WatchedThreshold.ALMOST_FINISHED.isWatched(590_000L, 600_000L, countImported = false)).isTrue()
    }
}
