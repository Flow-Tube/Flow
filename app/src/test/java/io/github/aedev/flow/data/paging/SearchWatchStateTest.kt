package io.github.aedev.flow.data.paging

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.data.local.dao.WatchProgress
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class SearchWatchStateTest {
    private fun video(
        id: String,
        isShort: Boolean = false,
    ) = Video(id, "Title $id", "Channel", "UC1", "", 600, 0, uploadDate = "", isShort = isShort)

    private fun progress(
        id: String,
        position: Long,
        duration: Long,
    ) = WatchProgress(id, position, duration, timestamp = 1L)

    @Test
    fun `a video counts as watched on the user's threshold`() {
        val videos = listOf(video("done"), video("half"), video("never"))
        val history = listOf(progress("done", 580_000, 600_000), progress("half", 300_000, 600_000))

        assertThat(watchedVideoIds(videos, history, WatchedThreshold.ALMOST_FINISHED)).containsExactly("done")
    }

    @Test
    fun `a stricter threshold leaves a nearly finished video unwatched`() {
        val videos = listOf(video("v"))
        val history = listOf(progress("v", 570_000, 600_000))

        assertThat(watchedVideoIds(videos, history, WatchedThreshold.PERCENT_90)).containsExactly("v")
        assertThat(watchedVideoIds(videos, history, WatchedThreshold.PERCENT_99)).isEmpty()
    }

    @Test
    fun `a Short counts as watched on the Shorts line, not the video threshold`() {
        val videos = listOf(video("s", isShort = true))
        val history = listOf(progress("s", 55_000, 60_000))

        assertThat(watchedVideoIds(videos, history, WatchedThreshold.PERCENT_99)).containsExactly("s")
    }

    @Test
    fun `an imported video counts as watched only when the viewer asks`() {
        val videos = listOf(video("imported"), video("never"))
        val history = listOf(progress("imported", 0L, 0L))

        assertThat(watchedVideoIds(videos, history, WatchedThreshold.ALMOST_FINISHED)).isEmpty()
        assertThat(watchedVideoIds(videos, history, WatchedThreshold.ALMOST_FINISHED, countImported = true)).containsExactly("imported")
    }
}
