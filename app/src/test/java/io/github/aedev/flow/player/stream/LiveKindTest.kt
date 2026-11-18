package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.shorts.reelExtraction
import org.junit.Test

/** What the player is told a video is, read off the two live flags of `videoDetails`. */
class LiveKindTest {
    private fun kind(
        isLive: Boolean?,
        isLiveContent: Boolean?,
    ): String {
        val base = reelExtraction(emptyList(), emptyList())
        val details = base.playerResponse.videoDetails!!.copy(isLive = isLive, isLiveContent = isLiveContent)
        return liveKindOf(base.copy(playerResponse = base.playerResponse.copy(videoDetails = details)))
    }

    @Test
    fun `a running stream is live`() {
        assertThat(kind(isLive = true, isLiveContent = true)).isEqualTo("LIVE_STREAM")
        assertThat(kind(isLive = true, isLiveContent = null)).isEqualTo("LIVE_STREAM")
    }

    @Test
    fun `an ended stream is post-live`() {
        assertThat(kind(isLive = false, isLiveContent = true)).isEqualTo("POST_LIVE_STREAM")
        assertThat(kind(isLive = null, isLiveContent = true)).isEqualTo("POST_LIVE_STREAM")
    }

    @Test
    fun `anything else is an ordinary video`() {
        assertThat(kind(isLive = null, isLiveContent = null)).isEqualTo("VIDEO_STREAM")
        assertThat(kind(isLive = false, isLiveContent = false)).isEqualTo("VIDEO_STREAM")
    }

    @Test
    fun `no video details is an ordinary video`() {
        val base = reelExtraction(emptyList(), emptyList())
        assertThat(liveKindOf(base.copy(playerResponse = base.playerResponse.copy(videoDetails = null)))).isEqualTo("VIDEO_STREAM")
    }
}
