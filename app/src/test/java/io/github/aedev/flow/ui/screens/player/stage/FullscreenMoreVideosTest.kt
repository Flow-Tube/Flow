package io.github.aedev.flow.ui.screens.player.stage

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class FullscreenMoreVideosTest {
    private fun video(
        id: String,
        isShort: Boolean = false,
    ) = Video(
        id = id,
        title = id,
        channelName = "",
        channelId = "",
        thumbnailUrl = "",
        duration = 60,
        viewCount = 0,
        uploadDate = "",
        isShort = isShort,
    )

    @Test
    fun `the row leaves out Shorts and repeats and keeps the order`() {
        val related = listOf(video("a"), video("s", isShort = true), video("b"), video("a"))

        assertThat(moreVideosFrom(related).map { it.id }).containsExactly("a", "b").inOrder()
    }
}
