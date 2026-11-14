package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class WatchLaterCleanupTest {
    private fun video(id: String) =
        Video(id = id, title = id, channelName = "", channelId = "", thumbnailUrl = "", duration = 600, viewCount = 0, uploadDate = "")

    private fun entry(
        id: String,
        positionMs: Long,
        durationMs: Long = 600_000L,
    ) = VideoHistoryEntry(videoId = id, position = positionMs, duration = durationMs, timestamp = 0L, title = id, thumbnailUrl = "")

    @Test
    fun `only videos past the watched threshold are picked`() {
        val watchLater = listOf(video("finished"), video("halfway"), video("never"), video("almost"))
        val history = listOf(entry("finished", 600_000L), entry("halfway", 300_000L), entry("almost", 590_000L))

        assertThat(watchedIds(watchLater, history, WatchedThreshold.ALMOST_FINISHED)).containsExactly("finished", "almost").inOrder()
        assertThat(watchedIds(watchLater, history, WatchedThreshold.PERCENT_99)).containsExactly("finished")
    }

    @Test
    fun `history of videos not in Watch later is ignored`() {
        assertThat(watchedIds(listOf(video("a")), listOf(entry("b", 600_000L)), WatchedThreshold.PERCENT_90)).isEmpty()
    }

    @Test
    fun `imported history is swept only when counted as watched`() {
        val watchLater = listOf(video("imported"))
        val history = listOf(entry("imported", 0L, durationMs = 0L))

        assertThat(watchedIds(watchLater, history, WatchedThreshold.ALMOST_FINISHED)).isEmpty()
        assertThat(watchedIds(watchLater, history, WatchedThreshold.ALMOST_FINISHED, countImported = true)).containsExactly("imported")
    }
}
