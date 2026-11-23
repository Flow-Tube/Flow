package io.github.aedev.flow.ui.screens.player.effects

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyLiveStreams
import org.junit.Test

class CurrentLiveStreamTest {
    @Test
    fun `a live stream with no HLS manifest is still live`() {
        val state = VideoPlayerUiState().applyLiveStreams(relatedVideos = emptyList(), hlsUrl = null)

        assertThat(state.isCurrentLiveStream()).isTrue()
    }

    @Test
    fun `a video that is not live is not`() {
        assertThat(VideoPlayerUiState().isCurrentLiveStream()).isFalse()
    }
}
