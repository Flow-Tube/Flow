package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opus
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9
import io.github.aedev.flow.player.stream.testUrl
import org.junit.Test

/** The screen's record of a quality pick: the streams it resolved to, the row they belong to, and whether Auto owns it. */
class SelectedQualityReducerTest {
    @Test
    fun `a fixed pick stores its streams and the row of the stream it got`() {
        val video = vp9(1440, 9_000_000)
        val audio = opus()

        val state = VideoPlayerUiState(isAdaptiveMode = true).applySelectedQuality(VideoQuality.Q_1080P, video, audio)

        assertThat(state.videoStream!!.testUrl).isEqualTo(video.testUrl)
        assertThat(state.audioStream!!.testUrl).isEqualTo(audio.testUrl)
        assertThat(state.selectedQuality).isEqualTo(VideoQuality.Q_1440P)
        assertThat(state.isAdaptiveMode).isFalse()
    }

    @Test
    fun `Auto with no fixed stream shows the Auto row`() {
        val state = VideoPlayerUiState(videoStream = h264(720, 2_500_000)).applySelectedQuality(VideoQuality.AUTO, null, null)

        assertThat(state.videoStream).isNull()
        assertThat(state.selectedQuality).isEqualTo(VideoQuality.AUTO)
        assertThat(state.isAdaptiveMode).isTrue()
    }
}
