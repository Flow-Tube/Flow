package io.github.aedev.flow.ui.tv.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class TvUpNextRowsTest {
    private fun video(id: String) =
        Video(id = id, title = id, channelName = "", channelId = "", thumbnailUrl = "", duration = 0, viewCount = 0L, uploadDate = "")

    private fun List<Video>.ids() = map { it.id }

    @Test
    fun `related videos follow the rest of the queue`() {
        val rows =
            upNextRows(listOf(video("a"), video("b"), video("c")), currentQueueIndex = 0, relatedVideos = listOf(video("r1"), video("r2")))

        assertThat(rows.upcoming.ids()).containsExactly("b", "c").inOrder()
        assertThat(rows.related.ids()).containsExactly("r1", "r2").inOrder()
    }

    @Test
    fun `a related video already in the queue is left out`() {
        val rows =
            upNextRows(listOf(video("a"), video("b")), currentQueueIndex = 0, relatedVideos = listOf(video("a"), video("b"), video("r")))

        assertThat(rows.related.ids()).containsExactly("r")
    }

    @Test
    fun `with no queue the row is the related videos`() {
        val rows = upNextRows(emptyList(), currentQueueIndex = -1, relatedVideos = listOf(video("r")))

        assertThat(rows.upcoming).isEmpty()
        assertThat(rows.related.ids()).containsExactly("r")
        assertThat(rows.isEmpty).isFalse()
    }

    @Test
    fun `the last queued video playing with nothing related leaves the row empty`() {
        assertThat(upNextRows(listOf(video("a")), currentQueueIndex = 0, relatedVideos = emptyList()).isEmpty).isTrue()
    }
}
